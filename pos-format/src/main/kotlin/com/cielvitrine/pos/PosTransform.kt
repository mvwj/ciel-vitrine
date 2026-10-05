package com.cielvitrine.pos

/**
 * POS-003, POS-009: Reversible transforms.
 * Public, self-describing, non-confidential transformations.
 */
object PosTransform {

    fun transform(
        transformId: Int,
        setId: ByteArray,
        originalOffset: Long,
        data: ByteArray,
        length: Int = data.size
    ): ByteArray {
        return when (transformId) {
            PosConstants.TRANSFORM_IDENTITY -> {
                data.copyOf(length)
            }
            PosConstants.TRANSFORM_POS_XOR_V1 -> {
                val out = ByteArray(length)
                for (i in 0 until length) {
                    val currentOffset = originalOffset + i
                    val setIdByte = setId[(currentOffset % 16).toInt()].toInt() and 0xFF
                    val offsetMod = (currentOffset % 251).toInt()
                    val key = (setIdByte xor offsetMod) and 0xFF
                    out[i] = (data[i].toInt() xor key).toByte()
                }
                out
            }
            else -> throw IllegalArgumentException("Unsupported POS transform ID: $transformId")
        }
    }

    /**
     * XOR is its own inverse; inverseTransform calls transform directly.
     */
    fun inverseTransform(
        transformId: Int,
        setId: ByteArray,
        originalOffset: Long,
        transformedData: ByteArray,
        length: Int = transformedData.size
    ): ByteArray {
        return transform(transformId, setId, originalOffset, transformedData, length)
    }
}
