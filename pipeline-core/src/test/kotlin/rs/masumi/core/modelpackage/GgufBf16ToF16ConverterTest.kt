package rs.masumi.core.modelpackage

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GgufBf16ToF16ConverterTest {
    @Test
    fun `converts BF16 tensor bytes and type without moving tensor data`() {
        val fixture = fixture(
            intArrayOf(0x3f80, 0xc000, 0x3eab, 0x7f80, 0x7fc0, 0x0001, 0x8001, 0x4780),
        )
        val path = Files.createTempFile("masumi-bf16", ".gguf")
        try {
            Files.write(path, fixture.bytes)

            GgufBf16ToF16Converter.convertInPlace(path)

            val converted = Files.readAllBytes(path)
            assertEquals(fixture.bytes.size, converted.size)
            assertEquals(1, readIntLe(converted, fixture.typeOffset))
            assertContentEquals(
                intArrayOf(0x3c00, 0xc000, 0x3558, 0x7c00, 0x7e00, 0x0000, 0x8000, 0x7c00),
                readWordsLe(converted, fixture.dataOffset, 8),
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `half conversion rounds ties to even`() {
        assertEquals(0x3c00, GgufBf16ToF16Converter.floatBitsToHalf(0x3f800000))
        assertEquals(0x7e00, GgufBf16ToF16Converter.floatBitsToHalf(0x7fc00000))
        assertEquals(0x0000, GgufBf16ToF16Converter.floatBitsToHalf(0x00010000))
        assertEquals(0x8000, GgufBf16ToF16Converter.floatBitsToHalf(0x80010000.toInt()))
    }

    private fun fixture(words: IntArray): Fixture {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.write("GGUF".encodeToByteArray())
        output.writeIntLe(3)
        output.writeLongLe(1)
        output.writeLongLe(1)
        output.writeString("general.alignment")
        output.writeIntLe(4)
        output.writeIntLe(32)
        output.writeString("weight")
        output.writeIntLe(1)
        output.writeLongLe(words.size.toLong())
        val typeOffset = bytes.size()
        output.writeIntLe(30)
        output.writeLongLe(0)
        while (bytes.size() % 32 != 0) output.writeByte(0)
        val dataOffset = bytes.size()
        words.forEach { output.writeShortLe(it) }
        output.flush()
        return Fixture(bytes.toByteArray(), typeOffset, dataOffset)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        writeLongLe(bytes.size.toLong())
        write(bytes)
    }

    private fun DataOutputStream.writeIntLe(value: Int) = writeInt(Integer.reverseBytes(value))

    private fun DataOutputStream.writeLongLe(value: Long) = writeLong(java.lang.Long.reverseBytes(value))

    private fun DataOutputStream.writeShortLe(value: Int) = writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun readWordsLe(bytes: ByteArray, offset: Int, count: Int): IntArray = IntArray(count) { index ->
        val wordOffset = offset + index * 2
        (bytes[wordOffset].toInt() and 0xff) or ((bytes[wordOffset + 1].toInt() and 0xff) shl 8)
    }

    private data class Fixture(val bytes: ByteArray, val typeOffset: Int, val dataOffset: Int)
}
