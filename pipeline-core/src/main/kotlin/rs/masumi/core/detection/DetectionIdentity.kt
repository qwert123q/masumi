package rs.masumi.core.detection

import rs.masumi.core.identity.SafeOpaqueId

object DetectionIdentity {
    fun pageArtifactKey(runArtifactKey: String, pageOrder: Int): String =
        SafeOpaqueId.child(runArtifactKey, "page", pageOrder)

    fun regionId(pageArtifactKey: String, regionIndex: Int): String =
        SafeOpaqueId.child(pageArtifactKey, "region", regionIndex)
}
