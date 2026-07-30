# Comic text segmentation model

Masumi bundles a segmentation-only derivative of
`comictextdetector.pt.onnx` from the
[manga-image-translator beta-0.3 release](https://github.com/zyddnys/manga-image-translator/releases/tag/beta-0.3).
The upstream project and Masumi are licensed under GPL-3.0.

Pinned upstream package:

- Repository: `zyddnys/manga-image-translator`
- Release: `beta-0.3`
- File: `comictextdetector.pt.onnx`
- Size: `94,669,756` bytes
- SHA-256: `1a86ace74961413cbd650002e7bb4dcec4980ffa21b2f19b86933372071d718f`
- ONNX opset: 11

Bundled derivative:

- File: `app/src/main/assets/models/comic-text-segmenter-512.onnx`
- Size: `65,568,382` bytes
- SHA-256: `688cb2b55bc14e29957bb4dad768e7420a4b1f740b84ffadc83ecaac63846485`
- Input: `images`, float32 `[1, 3, 512, 512]`
- Output: `seg`, float32 `[1, 1, 512, 512]`

The derivative removes the unused bounding-box and detection outputs from the
ONNX graph, changes the segmentation input/output spatial dimensions from 1024
to 512, and re-infers stale intermediate tensor shapes at that resolution. No
weights are quantized or retrained. Reproduce it with:

```sh
python3 -m pip install onnx==1.22.0
python3 tools/build-comic-text-segmenter.py \
  /path/to/comictextdetector.pt.onnx \
  app/src/main/assets/models/comic-text-segmenter-512.onnx
```
