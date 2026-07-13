package rs.masumi.core.importer

import java.util.UUID

fun interface IdSource {
    fun nextId(): String
}

object UuidIdSource : IdSource {
    override fun nextId(): String = UUID.randomUUID().toString()
}
