"""Quantize the exported SCHP ATR model with QOperator (for XNNPACK probe).

Run:
    conda run -n agent python tools/model-ab/quantize_schp_qoperator.py
"""

from __future__ import annotations

import numpy as np
import onnxruntime as ort
from PIL import Image
from onnxruntime.quantization import QuantFormat, QuantType, quantize_static

from export_schp_onnx import (
    INPUT_SIZE,
    MEAN,
    ROOT,
    STD,
    OUT_DIR,
    CalibrationReader,
)

FP32 = OUT_DIR / "schp_atr_fp32.onnx"
QOP = OUT_DIR / "schp_atr_int8_qoperator.onnx"


def main() -> None:
    calibration = sorted((ROOT / "images").glob("*.jpg"))
    if not calibration:
        raise SystemExit("no calibration images under images/")
    quantize_static(
        str(FP32),
        str(QOP),
        CalibrationReader(calibration),
        quant_format=QuantFormat.QOperator,
        per_channel=True,
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QInt8,
    )
    print("qoperator:", QOP, f"{QOP.stat().st_size / 1024 / 1024:.1f} MiB")
    if not QOP.with_suffix(".onnx.data").exists():
        print("self-contained (no external data file)")

    session = ort.InferenceSession(str(QOP), providers=["CPUExecutionProvider"])
    image = Image.open(calibration[0]).convert("RGB").resize((INPUT_SIZE, INPUT_SIZE))
    array = np.asarray(image, dtype=np.float32) / 255.0
    array = (array - MEAN) / STD
    array = np.transpose(array, (2, 0, 1))[None]
    logits = session.run(None, {"input": np.ascontiguousarray(array, dtype=np.float32)})[0]
    parsing = np.argmax(logits[0], axis=0)
    unique, counts = np.unique(parsing, return_counts=True)
    print("classes:", {int(k): int(v) for k, v in zip(unique, counts)})


if __name__ == "__main__":
    main()
