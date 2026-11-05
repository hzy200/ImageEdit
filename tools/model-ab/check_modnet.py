"""Sanity-check the downloaded MODNet ONNX model on synthetic images."""

from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "models" / "modnet_photographic_portrait_matting.onnx"
SAMPLES = [
    ROOT / "tools/test-images/out/08_portrait_1080x1920.png",
    ROOT / "tools/test-images/out/09_backlight_1600x1200.jpg",
]


def main() -> None:
    session = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    print("inputs:", [(i.name, i.shape, i.type) for i in session.get_inputs()])
    print("outputs:", [(o.name, o.shape, o.type) for o in session.get_outputs()])
    for sample in SAMPLES:
        image = Image.open(sample).convert("RGB").resize((512, 512))
        array = np.asarray(image, dtype=np.float32) / 255.0
        array = (array - 0.5) / 0.5
        array = np.transpose(array, (2, 0, 1))[None]
        alpha = session.run(None, {"input": array})[0][0, 0]
        print(
            sample.name,
            "min=%.3f max=%.3f mean=%.3f foreground=%.3f"
            % (alpha.min(), alpha.max(), alpha.mean(), (alpha > 0.5).mean()),
        )


if __name__ == "__main__":
    main()
