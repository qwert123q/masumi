package rs.masumi.core.modelpackage

import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * Rewrites GGUF BF16 tensors as IEEE F16 without changing tensor lengths or offsets.
 *
 * The conversion is intentionally in-place inside an unpublished staging directory.
 * If the process is interrupted, the incomplete staging file is rejected by its
 * expected length and the next attempt resumes or restarts it safely.
 */
object GgufBf16ToF16Converter {
    private const val GGUF_VERSION_2 = 2
    private const val GGUF_VERSION_3 = 3
    private const val DEFAULT_ALIGNMENT = 32L
    private const val GGML_TYPE_F16 = 1
    private const val GGML_TYPE_BF16 = 30
    private const val BUFFER_BYTES = 4 * 1024 * 1024

    fun convertInPlace(path: Path) {
        RandomAccessFile(path.toFile(), "rw").use { file ->
            require(file.readUnsignedByte() == 'G'.code)
            require(file.readUnsignedByte() == 'G'.code)
            require(file.readUnsignedByte() == 'U'.code)
            require(file.readUnsignedByte() == 'F'.code)
            val version = file.readIntLe()
            require(version == GGUF_VERSION_2 || version == GGUF_VERSION_3) {
                "unsupported GGUF version"
            }
            val tensorCount = file.readCount("tensor count")
            val metadataCount = file.readCount("metadata count")
            var alignment = DEFAULT_ALIGNMENT
            repeatCount(metadataCount) {
                val key = file.readString()
                val type = file.readIntLe()
                if (key == "general.alignment" && type == ValueType.UINT32.id) {
                    alignment = file.readUnsignedIntLe()
                    require(alignment > 0L && alignment and (alignment - 1L) == 0L)
                } else {
                    file.skipValue(type)
                }
            }

            val tensors = ArrayList<Bf16Tensor>()
            repeatCount(tensorCount) {
                file.skipString()
                val dimensions = file.readIntLe()
                require(dimensions in 1..4) { "invalid GGUF tensor dimension count" }
                var elements = 1L
                repeat(dimensions) {
                    val dimension = file.readLongLe()
                    require(dimension > 0L)
                    elements = Math.multiplyExact(elements, dimension)
                }
                val typeOffset = file.filePointer
                val type = file.readIntLe()
                val tensorOffset = file.readLongLe()
                require(tensorOffset >= 0L)
                if (type == GGML_TYPE_BF16) {
                    tensors += Bf16Tensor(typeOffset, tensorOffset, elements)
                }
            }

            val dataStart = align(file.filePointer, alignment)
            val fileLength = file.length()
            val buffer = ByteArray(BUFFER_BYTES)
            tensors.forEach { tensor ->
                val byteLength = Math.multiplyExact(tensor.elements, 2L)
                val dataOffset = Math.addExact(dataStart, tensor.relativeOffset)
                val dataEnd = Math.addExact(dataOffset, byteLength)
                require(dataOffset >= dataStart && dataEnd <= fileLength) {
                    "GGUF tensor exceeds file length"
                }
                var converted = 0L
                while (converted < byteLength) {
                    val count = minOf(buffer.size.toLong(), byteLength - converted).toInt()
                    require(count and 1 == 0)
                    val chunkOffset = dataOffset + converted
                    file.seek(chunkOffset)
                    file.readFully(buffer, 0, count)
                    var index = 0
                    while (index < count) {
                        val bf16 = (buffer[index].toInt() and 0xff) or
                            ((buffer[index + 1].toInt() and 0xff) shl 8)
                        val half = floatBitsToHalf(bf16 shl 16)
                        buffer[index] = half.toByte()
                        buffer[index + 1] = (half ushr 8).toByte()
                        index += 2
                    }
                    file.seek(chunkOffset)
                    file.write(buffer, 0, count)
                    converted += count
                }
                file.seek(tensor.typeOffset)
                file.writeIntLe(GGML_TYPE_F16)
            }
            file.fd.sync()
        }
    }

    internal fun floatBitsToHalf(bits: Int): Int {
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xff
        val mantissa = bits and 0x7fffff
        if (exponent == 0xff) {
            if (mantissa == 0) return sign or 0x7c00
            return sign or 0x7c00 or (mantissa ushr 13).coerceAtLeast(1)
        }

        val halfExponent = exponent - 127 + 15
        if (halfExponent >= 0x1f) return sign or 0x7c00
        if (halfExponent <= 0) {
            if (halfExponent < -10) return sign
            val normalized = mantissa or 0x800000
            val shift = 14 - halfExponent
            var halfMantissa = normalized ushr shift
            val remainderMask = (1 shl shift) - 1
            val remainder = normalized and remainderMask
            val halfway = 1 shl (shift - 1)
            if (remainder > halfway || (remainder == halfway && halfMantissa and 1 != 0)) {
                halfMantissa++
            }
            return sign or halfMantissa
        }

        var half = sign or (halfExponent shl 10) or (mantissa ushr 13)
        val remainder = mantissa and 0x1fff
        if (remainder > 0x1000 || (remainder == 0x1000 && half and 1 != 0)) {
            half++
        }
        return half
    }

    private fun RandomAccessFile.skipValue(rawType: Int) {
        when (ValueType.from(rawType)) {
            ValueType.UINT8, ValueType.INT8, ValueType.BOOL -> skipExact(1L)
            ValueType.UINT16, ValueType.INT16 -> skipExact(2L)
            ValueType.UINT32, ValueType.INT32, ValueType.FLOAT32 -> skipExact(4L)
            ValueType.UINT64, ValueType.INT64, ValueType.FLOAT64 -> skipExact(8L)
            ValueType.STRING -> skipString()
            ValueType.ARRAY -> {
                val elementType = readIntLe()
                val count = readCount("array count")
                val fixedSize = ValueType.from(elementType).fixedSize
                if (fixedSize != null) {
                    skipExact(Math.multiplyExact(count, fixedSize))
                } else {
                    repeatCount(count) { skipValue(elementType) }
                }
            }
        }
    }

    private fun RandomAccessFile.skipString() {
        val length = readCount("string length")
        skipExact(length)
    }

    private fun RandomAccessFile.readString(): String {
        val length = readCount("string length")
        require(length <= 1_048_576L) { "GGUF metadata key is too long" }
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun RandomAccessFile.skipExact(byteCount: Long) {
        require(byteCount >= 0L)
        val target = Math.addExact(filePointer, byteCount)
        if (target > length()) throw EOFException("GGUF field exceeds file length")
        seek(target)
    }

    private fun RandomAccessFile.readCount(label: String): Long = readLongLe().also {
        require(it in 0..Int.MAX_VALUE.toLong()) { "invalid GGUF $label" }
    }

    private fun RandomAccessFile.readIntLe(): Int = Integer.reverseBytes(readInt())

    private fun RandomAccessFile.readUnsignedIntLe(): Long = readIntLe().toLong() and 0xffffffffL

    private fun RandomAccessFile.readLongLe(): Long = java.lang.Long.reverseBytes(readLong())

    private fun RandomAccessFile.writeIntLe(value: Int) = writeInt(Integer.reverseBytes(value))

    private fun repeatCount(count: Long, action: () -> Unit) {
        var index = 0L
        while (index < count) {
            action()
            index++
        }
    }

    private fun align(value: Long, alignment: Long): Long =
        Math.addExact(value, alignment - 1L) and -alignment

    private data class Bf16Tensor(
        val typeOffset: Long,
        val relativeOffset: Long,
        val elements: Long,
    )

    private enum class ValueType(val id: Int, val fixedSize: Long?) {
        UINT8(0, 1),
        INT8(1, 1),
        UINT16(2, 2),
        INT16(3, 2),
        UINT32(4, 4),
        INT32(5, 4),
        FLOAT32(6, 4),
        BOOL(7, 1),
        STRING(8, null),
        ARRAY(9, null),
        UINT64(10, 8),
        INT64(11, 8),
        FLOAT64(12, 8);

        companion object {
            fun from(id: Int): ValueType = entries.firstOrNull { it.id == id }
                ?: error("unsupported GGUF value type")
        }
    }
}
