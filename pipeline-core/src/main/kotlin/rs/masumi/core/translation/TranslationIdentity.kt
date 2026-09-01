package rs.masumi.core.translation

import rs.masumi.core.identity.SafeOpaqueId

object TranslationIdentity {
    fun regionId(ocrRegionId: String): String = SafeOpaqueId.require(ocrRegionId, "ocrRegionId")
}
