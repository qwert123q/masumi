package rs.masumi.core.typesetting

import rs.masumi.core.identity.SafeOpaqueId

object TypesettingIdentity {
    fun pageArtifactKey(runArtifactKey: String, pageOrder: Int): String =
        SafeOpaqueId.child(runArtifactKey, "page", pageOrder)
}
