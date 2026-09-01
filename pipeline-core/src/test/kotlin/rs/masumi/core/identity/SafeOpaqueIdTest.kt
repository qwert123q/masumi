package rs.masumi.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SafeOpaqueIdTest {
    @Test
    fun `uuid and legacy digest shaped values are ordinary safe IDs`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val legacy = "a".repeat(64)

        assertEquals(uuid, SafeOpaqueId.require(uuid, "runId"))
        assertEquals(legacy, SafeOpaqueId.require(legacy, "runId"))
    }

    @Test
    fun `path separators and oversized values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SafeOpaqueId.require("../run", "runId")
        }
        assertFailsWith<IllegalArgumentException> {
            SafeOpaqueId.require("r".repeat(129), "runId")
        }
    }

    @Test
    fun `child IDs are structural and safe`() {
        assertEquals(
            "run-1.page.0007",
            SafeOpaqueId.child("run-1", "page", 7),
        )
    }
}
