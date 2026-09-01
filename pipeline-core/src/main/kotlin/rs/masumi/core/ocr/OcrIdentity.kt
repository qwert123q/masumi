package rs.masumi.core.ocr

import rs.masumi.core.identity.SafeOpaqueId

object OcrIdentity {
    fun pageArtifactKey(runArtifactKey: String, pageOrder: Int): String =
        SafeOpaqueId.child(runArtifactKey, "page", pageOrder)

    fun regionId(pageArtifactKey: String, regionIndex: Int): String =
        SafeOpaqueId.child(pageArtifactKey, "region", regionIndex)
}
