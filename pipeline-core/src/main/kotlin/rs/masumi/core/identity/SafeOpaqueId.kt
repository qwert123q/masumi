package rs.masumi.core.identity

object SafeOpaqueId {
    private val pattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val childPart = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")

    fun require(value: String, field: String = "id"): String {
        require(pattern.matches(value)) { "$field must be a safe opaque ID" }
        return value
    }

    fun isValid(value: String): Boolean = pattern.matches(value)

    fun child(parent: String, kind: String, index: Int): String {
        require(parent.length <= MAX_PARENT_LENGTH) { "parent ID is too long for a structural child ID" }
        require(childPart.matches(kind)) { "child kind must be safe" }
        require(index >= 0) { "child index must not be negative" }
        return require("$parent.$kind.${index.toString().padStart(4, '0')}", "childId")
    }

    private const val MAX_PARENT_LENGTH = 80
}
