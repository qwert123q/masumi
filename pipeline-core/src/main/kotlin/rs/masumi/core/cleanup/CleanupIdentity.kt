package rs.masumi.core.cleanup

import rs.masumi.core.identity.SafeOpaqueId

object CleanupIdentity {
    fun pageArtifactKey(runArtifactKey: String, pageOrder: Int): String =
        SafeOpaqueId.child(runArtifactKey, "page", pageOrder)
}
