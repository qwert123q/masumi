package rs.masumi.core.modelpackage

import rs.masumi.core.cleanup.CleanupNeuralModelRef

data class NeuralInpainterModelDescriptor(
    val modelId: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val assetPath: String,
    val byteLength: Long,
    val license: String,
    val opset: Int,
    val runtimeRevision: String,
) {
    fun toModelRef(): CleanupNeuralModelRef = CleanupNeuralModelRef(
        modelId = modelId,
        repository = repository,
        revision = revision,
        fileName = fileName,
        byteLength = byteLength,
        license = license,
        opset = opset,
        runtimeRevision = runtimeRevision,
    )
}

/** Bundled AOT-GAN artifact identified by its explicit upstream and runtime revisions. */
object PinnedAotInpainter {
    val descriptor = NeuralInpainterModelDescriptor(
        modelId = "lemon-aot-folded",
        repository = "lemondouble/lemon-manga-translator",
        revision = "e8c08f38f188db684fdc32c4cf88627c7df92096",
        fileName = "aot-inpainting.onnx",
        assetPath = "models/aot-inpainting.onnx",
        byteLength = 23_009_155L,
        license = "GPL-3.0-only",
        opset = 17,
        runtimeRevision = "onnxruntime-android:1.27.0",
    )
}
