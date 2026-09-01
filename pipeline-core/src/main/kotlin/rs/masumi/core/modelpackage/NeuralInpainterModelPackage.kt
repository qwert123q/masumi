package rs.masumi.core.modelpackage

import rs.masumi.core.cleanup.CleanupNeuralModelRef

data class NeuralInpainterModelDescriptor(
    val modelId: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val assetPath: String,
    val byteLength: Long,
    val sha256: String,
    val license: String,
    val opset: Int,
    val runtimeRevision: String,
) {
    fun toModelRef(): CleanupNeuralModelRef = CleanupNeuralModelRef(
        modelId = modelId,
        repository = repository,
        revision = revision,
        fileName = fileName,
        sha256 = sha256,
        byteLength = byteLength,
        license = license,
        opset = opset,
        runtimeRevision = runtimeRevision,
    )
}

/** Exact bundled AOT-GAN artifact; changing any byte invalidates cleanup reuse. */
object PinnedAotInpainter {
    val descriptor = NeuralInpainterModelDescriptor(
        modelId = "lemon-aot-folded",
        repository = "lemondouble/lemon-manga-translator",
        revision = "e8c08f38f188db684fdc32c4cf88627c7df92096",
        fileName = "aot-inpainting.onnx",
        assetPath = "models/aot-inpainting.onnx",
        byteLength = 23_009_155L,
        sha256 = "e0d8f438ca9567eccc9d358963427601b6f64a650cbe6189ec82fc43830a0390",
        license = "GPL-3.0-only",
        opset = 17,
        runtimeRevision = "onnxruntime-android:1.27.0",
    )
}
