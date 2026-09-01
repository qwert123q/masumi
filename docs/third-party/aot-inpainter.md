# AOT manga inpainter model

Masumi bundles a byte-for-byte renamed copy of `aot_folded.onnx` from the
[`lemondouble/lemon-manga-translator`](https://huggingface.co/lemondouble/lemon-manga-translator)
model repository. The model card traces the weights to the AOT-GAN architecture,
the manga fine-tune distributed by `zyddnys/manga-image-translator` beta-0.3,
and the refactor by `mayocream/aot-inpainting`. The repository licenses all
models under GPL-3.0, which is the conservative license Masumi records for this
artifact.

Pinned artifact:

- Repository: `lemondouble/lemon-manga-translator`
- Revision: `e8c08f38f188db684fdc32c4cf88627c7df92096`
- Upstream path: `onnx/aot-inpainting/aot_folded.onnx`
- Local path: `app/src/main/assets/models/aot-inpainting.onnx`
- Size: `23,009,155` bytes
- SHA-256: `e0d8f438ca9567eccc9d358963427601b6f64a650cbe6189ec82fc43830a0390`
- License: GPL-3.0-only
- ONNX opset: 17
- Inputs: `image`, float32 `[1, 3, H, W]`; `mask`, float32 `[1, 1, H, W]`
- Output: `inpainted`, float32 `[1, 3, H, W]`

The graph uses dynamic spatial dimensions that must be multiples of eight and
folds the AOT `ScaledWSConv2d` weight standardization for inference. Reproduce
both bundled model assets with:

```bash
python3 -m pip install onnx==1.22.0
tools/prepare-bundled-models.sh
```
