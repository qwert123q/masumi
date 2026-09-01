package rs.masumi.core.translation

import rs.masumi.core.identity.SafeOpaqueId

object TranslationArtifactIdentity {
    fun pageArtifactKey(runArtifactKey: String, pageOrder: Int): String =
        SafeOpaqueId.child(runArtifactKey, "page", pageOrder)

    fun windowArtifactKey(runArtifactKey: String, windowIndex: Int): String =
        SafeOpaqueId.child(runArtifactKey, "window", windowIndex)
}
