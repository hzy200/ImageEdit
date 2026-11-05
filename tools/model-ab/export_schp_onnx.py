"""Export the SCHP ATR checkpoint to ONNX and INT8-quantize it.

Run:
    conda run -n agent python tools/model-ab/export_schp_onnx.py

The SCHP repository is cloned into tools/model-ab/schp-src (MIT).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn as nn
from PIL import Image
from onnxruntime.quantization import (
    CalibrationDataReader,
    QuantFormat,
    QuantType,
    quantize_static,
)

ROOT = Path(__file__).resolve().parents[2]
REPO = ROOT / "tools/model-ab/schp-src"
MODEL_DIR = ROOT / "models"
OUT_DIR = ROOT / "tools/model-ab/out"
OUT_DIR.mkdir(parents=True, exist_ok=True)
INPUT_SIZE = 512
NUM_CLASSES = 18

LABELS = [
    "Background", "Hat", "Hair", "Sunglasses", "Upper-clothes", "Skirt",
    "Pants", "Dress", "Belt", "Left-shoe", "Right-shoe", "Face",
    "Left-leg", "Right-leg", "Left-arm", "Right-arm", "Bag", "Scarf",
]
MEAN = np.array([0.406, 0.456, 0.485], dtype=np.float32)
STD = np.array([0.225, 0.224, 0.229], dtype=np.float32)


class ExportWrapper(nn.Module):
    def __init__(self, model: nn.Module) -> None:
        super().__init__()
        self.model = model
        self.upsample = nn.Upsample(
            size=(INPUT_SIZE, INPUT_SIZE),
            mode="bilinear",
            align_corners=True,
        )

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        outputs = self.model(image)
        # SCHP returns [[parsing, fusion], [edge]]; use the fusion logits.
        fusion = outputs[0][-1]
        return self.upsample(fusion)


class CalibrationReader(CalibrationDataReader):
    def __init__(self, image_paths: list[Path]) -> None:
        self._items = [self._preprocess(path) for path in image_paths]
        self._index = 0

    def _preprocess(self, path: Path) -> dict[str, np.ndarray]:
        image = Image.open(path).convert("RGB").resize((INPUT_SIZE, INPUT_SIZE))
        array = np.asarray(image, dtype=np.float32) / 255.0
        array = (array - MEAN) / STD
        array = np.transpose(array, (2, 0, 1))[None]
        return {"input": np.ascontiguousarray(array, dtype=np.float32)}

    def get_next(self):
        if self._index >= len(self._items):
            return None
        item = self._items[self._index]
        self._index += 1
        return item

    def rewind(self) -> None:
        self._index = 0


def build_model() -> nn.Module:
    sys.path.insert(0, str(REPO))
    import networks  # noqa: WPS433

    model = networks.init_model("resnet101", num_classes=NUM_CLASSES, pretrained=None)
    checkpoint = torch.load(
        MODEL_DIR / "exp-schp-201908301523-atr.pth",
        map_location="cpu",
        weights_only=False,
    )
    state_dict = checkpoint["state_dict"]
    cleaned = {key[7:] if key.startswith("module.") else key: value for key, value in state_dict.items()}
    model.load_state_dict(cleaned)
    model.eval()
    return model


def main() -> None:
    model = ExportWrapper(build_model()).eval()
    dummy = torch.randn(1, 3, INPUT_SIZE, INPUT_SIZE)
    fp32_path = OUT_DIR / "schp_atr_fp32.onnx"
    int8_path = OUT_DIR / "schp_atr_int8.onnx"

    try:
        # New TorchDynamo exporter handles adaptive_avg_pool2d with non-factor sizes.
        torch.onnx.export(
            model,
            dummy,
            str(fp32_path),
            input_names=["input"],
            output_names=["logits"],
            opset_version=18,
            dynamo=True,
        )
    except Exception as error:
        print("dynamo export failed, falling back to legacy:", error)
        torch.onnx.export(
            model,
            dummy,
            str(fp32_path),
            input_names=["input"],
            output_names=["logits"],
            opset_version=13,
            do_constant_folding=True,
            dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}},
            training=torch.onnx.TrainingMode.EVAL,
        )
    print("fp32 onnx:", fp32_path, f"{fp32_path.stat().st_size / 1024 / 1024:.1f} MiB")

    onnx.checker.check_model(str(fp32_path))

    # Validate FP32 against PyTorch.
    with torch.no_grad():
        reference = model(dummy).numpy()
    session = ort.InferenceSession(str(fp32_path), providers=["CPUExecutionProvider"])
    exported = session.run(None, {"input": dummy.numpy()})[0]
    print("fp32 max_abs_diff:", float(np.max(np.abs(reference - exported))))

    calibration = sorted((ROOT / "images").glob("*.jpg"))
    if not calibration:
        raise SystemExit("no calibration images under images/")
    reader = CalibrationReader(calibration)
    quantize_static(
        str(fp32_path),
        str(int8_path),
        reader,
        quant_format=QuantFormat.QDQ,
        per_channel=True,
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QInt8,
    )
    print("int8 onnx:", int8_path, f"{int8_path.stat().st_size / 1024 / 1024:.1f} MiB")

    session = ort.InferenceSession(str(int8_path), providers=["CPUExecutionProvider"])
    sample = calibration[0]
    image = Image.open(sample).convert("RGB").resize((INPUT_SIZE, INPUT_SIZE))
    array = np.asarray(image, dtype=np.float32) / 255.0
    array = (array - MEAN) / STD
    array = np.transpose(array, (2, 0, 1))[None]
    logits = session.run(None, {"input": np.ascontiguousarray(array, dtype=np.float32)})[0]
    parsing = np.argmax(logits[0], axis=0).astype(np.uint8)
    unique, counts = np.unique(parsing, return_counts=True)
    print("sample classes:", {LABELS[int(k)]: int(v) for k, v in zip(unique, counts)})
    Image.fromarray(parsing, mode="L").save(OUT_DIR / "schp_atr_mask_preview.png")

    (OUT_DIR / "schp_atr_labels.json").write_text(
        json.dumps({"labels": LABELS, "input_size": INPUT_SIZE}, indent=2),
        encoding="utf-8",
    )
    print("done")


if __name__ == "__main__":
    main()
