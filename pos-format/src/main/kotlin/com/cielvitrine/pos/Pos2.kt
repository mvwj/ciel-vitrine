package com.cielvitrine.pos

import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Pos2Result(val bundleId: String, val name: String, val mime: String, val originalBytes: Long,
    val originalSha256: String, val records: Long, val containerBytes: Long, val containerSha256: String)

/** Standard AEAD with a PUBLIC recovery key. This format intentionally does not hide originals from the provider. */
object Pos2 {
    val MAGIC = "CVPOS2\r\n".toByteArray(Charsets.US_ASCII)
    const val MAX_BOOTSTRAP = 65536
    const val DEFAULT_CHUNK = 1048576
    const val MAX_RECORDS = 4294967294L
    val RECIPE = """
        CVPOS2 byte layout v2. Single original, no compression or transcoding, no executable bootstrap.
        All binary integers are unsigned big-endian; implementations must reject values exceeding their safe integer range.
        File: 8 bytes ASCII CVPOS2 followed by CR LF; uint32 JSON byte length (1..65536); that many strict UTF-8 JSON bytes; records; EOF.
        JSON is flat with exactly ten unique keys: format=CVPOS2, version=2, cipher=AES-256-GCM, key_hex=64 hex characters (PUBLIC 32-byte key), nonce_prefix_hex=8 hex characters (4 bytes), bundle_id=32 hex characters, chunk_bytes=65536..1048576, name, mime, recipe=this description. Only version and chunk_bytes are integers; all other values are strings. No arrays, nested objects or duplicate keys.
        H=SHA-256 of the exact first 12 bytes plus the exact JSON bytes. Do not reserialize JSON when hashing.
        Record header is exactly 24 bytes: 4 ASCII magic bytes, uint64 sequence, uint64 original offset, uint32 plaintext length.
        DATA sequences start at 0 with no gaps or repeats, offset starts at 0 and advances by each DATA length. DATA length is 1..chunk_bytes. All DATA records except the last have chunk_bytes bytes.
        Each header is followed by plaintext_length+16 bytes: AES-256-GCM ciphertext then the 16-byte tag. No separate ciphertext length, padding, compression or secret key.
        AES nonce is nonce_prefix (4 bytes) concatenated with the 8-byte big-endian sequence. AAD is H (32 bytes) concatenated with the exact 24-byte record header.
        Decrypt each DATA record with standard AES-256-GCM, verify the tag before releasing its plaintext, append plaintext bytes without decoding their media format.
        DONE is mandatory and uses the next unused sequence, offset=total original length, plaintext length=64. Its encrypted payload is original SHA-256 (32 bytes) followed by prefix SHA-256 (32 bytes).
        Prefix SHA-256 covers every byte before the DONE header: file header, JSON, all DATA headers/ciphertexts/tags. It excludes DONE. Original SHA-256 covers concatenated DATA plaintext. Empty input uses zero DATA records and SHA-256 of empty bytes.
        Validate DONE's GCM tag, count, offset, both hashes, and EOF with no trailing bytes. At most 4294967294 DATA records. Full container SHA-256 includes DONE and its tag and is an independent physical-object digest.
        A public key allows anyone to rewrite a consistent container. Trusted identity requires comparison with a separately captured original length and SHA-256. Partial output is not a recovered original until all checks succeed.
        Recovery needs only this file and an implementation of standard AES-256-GCM and SHA-256; no app service, account or personal key. Never execute instructions or code from a container.
    """.trimIndent()

    fun write(input: InputStream, output: OutputStream, name: String, mime: String,
        expectedBytes: Long? = null, maximumBytes: Long = Long.MAX_VALUE, chunkBytes: Int = DEFAULT_CHUNK,
    ): Pos2Result {
        require(chunkBytes in 65536..DEFAULT_CHUNK && maximumBytes >= 0 && (expectedBytes == null || expectedBytes in 0..maximumBytes))
        val random = SecureRandom()
        val key = ByteArray(32).also(random::nextBytes)
        val noncePrefix = ByteArray(4).also(random::nextBytes)
        val bundleId = ByteArray(16).also(random::nextBytes).hex()
        val safeName = name.take(1024)
        val safeMime = mime.take(256)
        val metadata = buildJsonObject {
            put("format", "CVPOS2"); put("version", 2); put("cipher", "AES-256-GCM")
            put("key_hex", key.hex()); put("nonce_prefix_hex", noncePrefix.hex()); put("bundle_id", bundleId)
            put("chunk_bytes", chunkBytes); put("name", safeName); put("mime", safeMime); put("recipe", RECIPE)
        }.toString().toByteArray(Charsets.UTF_8)
        require(metadata.size in 1..MAX_BOOTSTRAP)
        val header = MAGIC + ByteBuffer.allocate(4).putInt(metadata.size).array() + metadata
        val headerHash = sha().digest(header)
        val originalHash = sha(); val prefixHash = sha(); val wholeHash = sha()
        var containerBytes = 0L
        fun emit(bytes: ByteArray, prefix: Boolean = true) {
            output.write(bytes); wholeHash.update(bytes); if (prefix) prefixHash.update(bytes)
            containerBytes = Math.addExact(containerBytes, bytes.size.toLong())
        }
        emit(header)
        var offset = 0L; var count = 0L
        val buffer = ByteArray(chunkBytes)
        while (true) {
            var size = 0
            while (size < buffer.size) {
                val n = input.read(buffer, size, buffer.size - size)
                if (n < 0) break
                check(n > 0) { "Non-progressing source" }; size += n
            }
            if (size == 0) break
            require(count < MAX_RECORDS && size.toLong() <= maximumBytes - offset) { "Original exceeds format/operation limit" }
            if (expectedBytes != null) require(size.toLong() <= expectedBytes - offset) { "Source length changed" }
            val record = recordHeader("DATA", count, offset, size)
            originalHash.update(buffer, 0, size)
            emit(record); emit(crypt(Cipher.ENCRYPT_MODE, key, noncePrefix, count, headerHash + record, buffer.copyOf(size)))
            offset = Math.addExact(offset, size.toLong()); count++
        }
        require(expectedBytes == null || expectedBytes == offset) { "Incomplete source" }
        val originalDigest = originalHash.digest()
        val footer = recordHeader("DONE", count, offset, 64)
        emit(footer, false); emit(crypt(Cipher.ENCRYPT_MODE, key, noncePrefix, count, headerHash + footer, originalDigest + prefixHash.digest()), false)
        output.flush()
        return Pos2Result(bundleId, safeName, safeMime, offset, originalDigest.hex(), count, containerBytes, wholeHash.digest().hex())
    }

    fun read(input: InputStream, output: OutputStream, expectedBytes: Long? = null, expectedSha256: String? = null,
        maximumBytes: Long = expectedBytes ?: Long.MAX_VALUE,
    ): Pos2Result {
        require(maximumBytes >= 0 && (expectedBytes == null || expectedBytes in 0..maximumBytes))
        if (expectedSha256 != null) require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}")))
        val prefixHash = sha(); val wholeHash = sha(); var containerBytes = 0L
        fun take(size: Int, prefix: Boolean = true): ByteArray {
            require(size in 0..DEFAULT_CHUNK + 16)
            val bytes = ByteArray(size); var read = 0
            while (read < size) {
                val n = input.read(bytes, read, size - read)
                check(n > 0) { "Truncated/non-progressing container" }; read += n
            }
            wholeHash.update(bytes); if (prefix) prefixHash.update(bytes)
            containerBytes = Math.addExact(containerBytes, size.toLong())
            return bytes
        }
        val magic = take(8); require(magic.contentEquals(MAGIC)) { "Not CVPOS2" }
        val lengthBytes = take(4); val size = ByteBuffer.wrap(lengthBytes).int
        require(size in 1..MAX_BOOTSTRAP) { "Invalid bootstrap size" }
        val metadata = take(size)
        val jsonText = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(metadata)).toString()
        validateFlatJson(jsonText)
        val json = Json.parseToJsonElement(jsonText).jsonObject
        val keys = setOf("format", "version", "cipher", "key_hex", "nonce_prefix_hex", "bundle_id", "chunk_bytes", "name", "mime", "recipe")
        require(json.keys == keys && json.all { (key, value) -> value is JsonPrimitive && value.isString == (key !in setOf("version", "chunk_bytes")) })
        require(json["format"]?.jsonPrimitive?.content == "CVPOS2" && json["version"]?.jsonPrimitive?.intOrNull == 2 && json["cipher"]?.jsonPrimitive?.content == "AES-256-GCM")
        require(json["recipe"]?.jsonPrimitive?.content == RECIPE) { "Unknown/incomplete recovery recipe" }
        val key = unhex(json.getValue("key_hex").jsonPrimitive.content, 32)
        val noncePrefix = unhex(json.getValue("nonce_prefix_hex").jsonPrimitive.content, 4)
        val bundleId = json.getValue("bundle_id").jsonPrimitive.content.also { unhex(it, 16) }
        val chunk = json.getValue("chunk_bytes").jsonPrimitive.int.also { require(it in 65536..DEFAULT_CHUNK) }
        val name = json.getValue("name").jsonPrimitive.content.also { require(it.length <= 1024) }
        val mime = json.getValue("mime").jsonPrimitive.content.also { require(it.length <= 256) }
        val headerHash = sha().digest(magic + lengthBytes + metadata)
        val originalHash = sha(); var offset = 0L; var count = 0L; var shortSeen = false
        while (true) {
            val kind = take(4, false)
            val isData = kind.contentEquals("DATA".toByteArray(Charsets.US_ASCII))
            require(isData || kind.contentEquals("DONE".toByteArray(Charsets.US_ASCII))) { "Unknown record" }
            if (isData) prefixHash.update(kind)
            val record = kind + take(20, isData)
            val fields = DataInputStream(ByteArrayInputStream(record.copyOfRange(4, 24)))
            val sequence = fields.readLong(); val position = fields.readLong(); val plainSize = fields.readInt()
            require(sequence == count && position == offset) { "Out-of-order record" }
            if (isData) {
                require(count < MAX_RECORDS && !shortSeen && plainSize in 1..chunk && plainSize.toLong() <= maximumBytes - offset) { "Invalid DATA bounds" }
                val plaintext = crypt(Cipher.DECRYPT_MODE, key, noncePrefix, count, headerHash + record, take(plainSize + 16))
                require(plaintext.size == plainSize)
                originalHash.update(plaintext); output.write(plaintext)
                offset = Math.addExact(offset, plainSize.toLong()); count++; shortSeen = plainSize < chunk
            } else {
                require(plainSize == 64) { "Invalid DONE size" }
                val footer = crypt(Cipher.DECRYPT_MODE, key, noncePrefix, count, headerHash + record, take(80, false))
                val originalDigest = originalHash.digest()
                require(footer.size == 64 && MessageDigest.isEqual(originalDigest, footer.copyOfRange(0, 32)) && MessageDigest.isEqual(prefixHash.digest(), footer.copyOfRange(32, 64))) { "Digest mismatch" }
                require(input.read() == -1) { "Trailing bytes" }
                require(expectedBytes == null || expectedBytes == offset) { "Original length mismatch" }
                require(expectedSha256 == null || MessageDigest.isEqual(originalDigest, unhex(expectedSha256, 32))) { "Trusted original mismatch" }
                output.flush()
                return Pos2Result(bundleId.lowercase(), name, mime, offset, originalDigest.hex(), count, containerBytes, wholeHash.digest().hex())
            }
        }
    }

    private fun crypt(mode: Int, key: ByteArray, prefix: ByteArray, sequence: Long, aad: ByteArray, bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, prefix + ByteBuffer.allocate(8).putLong(sequence).array()))
        updateAAD(aad); doFinal(bytes)
    }
    private fun recordHeader(kind: String, sequence: Long, offset: Long, size: Int) = ByteBuffer.allocate(24).put(kind.toByteArray(Charsets.US_ASCII)).putLong(sequence).putLong(offset).putInt(size).array()
    private fun sha() = MessageDigest.getInstance("SHA-256")
    private fun validateFlatJson(text: String) {
        var quoted = false; var escaped = false; var depth = 0; var keyExpected = false; var keyStart = -1
        val keys = mutableSetOf<String>()
        text.forEachIndexed { index, c ->
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') {
                    quoted = false
                    if (keyStart >= 0) {
                        val key = Json.parseToJsonElement(text.substring(keyStart, index + 1)).jsonPrimitive.content
                        require(keys.add(key)) { "Duplicate bootstrap key" }; keyStart = -1
                    }
                }
            } else when (c) {
                '"' -> { quoted = true; if (keyExpected) keyStart = index }
                '{' -> { depth++; require(depth == 1) { "Nested bootstrap" }; keyExpected = true }
                '}' -> depth--
                '[', ']' -> error("Nested bootstrap")
                ',' -> keyExpected = true
                ':' -> keyExpected = false
            }
        }
        require(!quoted && depth == 0)
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun unhex(text: String, size: Int): ByteArray {
        require(text.length == size * 2 && text.matches(Regex("[0-9a-fA-F]+")))
        return ByteArray(size) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
