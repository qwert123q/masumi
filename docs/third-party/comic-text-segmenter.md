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
- ONNX opset: 11

Bundled derivative:

- File: `app/src/main/assets/models/comic-text-segmenter-512.onnx`
- Size: `65,568,382` bytes
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
