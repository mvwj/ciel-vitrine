package com.cielvitrine.pos.refdecoder

import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independent implementation: no imports from the encoder or any Ciel Vitrine module. */
fun decodePos2(source: File, destination: File? = null): DecodeResult {
    require(destination == null || source.canonicalFile != destination.canonicalFile)
    var created = false
    try {
        val output = destination?.let { Files.newOutputStream(it.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).also { created = true } }
            ?: OutputStream.nullOutputStream()
        return output.use { sink -> source.inputStream().use { decodePos2Stream(it, sink) } }
    } catch (e: Exception) { if (created) Files.deleteIfExists(destination!!.toPath()); throw e }
}

private fun decodePos2Stream(input: InputStream, output: OutputStream): DecodeResult {
    fun exact(length: Int): ByteArray {
        require(length in 0..1048592)
        val bytes = ByteArray(length); var offset = 0
        while (offset < length) { val n = input.read(bytes, offset, length - offset); check(n > 0) { "Truncated input" }; offset += n }
        return bytes
    }
    val fixed = exact(12)
    val magic = fixed.copyOfRange(0, 8)
    val v3 = magic.contentEquals("CVPOS3\r\n".toByteArray())
    require(v3 || magic.contentEquals("CVPOS2\r\n".toByteArray()))
    val jsonSize = ByteBuffer.wrap(fixed, 8, 4).int
    require(jsonSize in 1..65536)
    val jsonBytes = exact(jsonSize)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(jsonBytes)).toString()
    checkFlatUniqueFields(text)
    val meta = Json.parseToJsonElement(text).jsonObject
    val timeKeys = setOf("mtime_seconds", "mtime_nanos", "creation_millis", "media_added_millis", "media_taken_millis")
    require(meta.keys == setOf("format", "version", "cipher", "key_hex", "nonce_prefix_hex", "bundle_id", "chunk_bytes", "name", "mime", "recipe") + if (v3) timeKeys else emptySet())
    require(meta.all { (k, v) -> v is JsonPrimitive && v.isString == (k !in setOf("version", "chunk_bytes")) })
    require(meta.getValue("format").jsonPrimitive.content == (if (v3) "CVPOS3" else "CVPOS2") && meta.getValue("version").jsonPrimitive.int == (if (v3) 3 else 2) && meta.getValue("cipher").jsonPrimitive.content == "AES-256-GCM")
    val dates = if (v3) timeKeys.associateWith { meta.getValue(it).jsonPrimitive.content } else emptyMap()
    if (v3) {
        fun number(key: String): Long? = dates.getValue(key).let { value ->
            if (value.isEmpty()) null else { require(value.matches(Regex("0|-?[1-9][0-9]{0,17}"))); value.toLong() }
        }
        val seconds = number("mtime_seconds"); val nanos = number("mtime_nanos")
        require((seconds == null) == (nanos == null))
        require(seconds == null || seconds in -62135596800L..253402300799L && nanos!! in 0..999999999)
        for (key in timeKeys - setOf("mtime_seconds", "mtime_nanos")) require(number(key)?.let { it in 0..253402300799999L } != false)
    }
    fun hex(field: String, bytes: Int): ByteArray {
        val value = meta.getValue(field).jsonPrimitive.content
        require(value.matches(Regex("[0-9a-fA-F]{${bytes * 2}}")))
        return ByteArray(bytes) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    val key = SecretKeySpec(hex("key_hex", 32), "AES")
    val nonce = hex("nonce_prefix_hex", 4)
    hex("bundle_id", 16)
    val chunk = meta.getValue("chunk_bytes").jsonPrimitive.int.also { require(it in 65536..1048576) }
    require(meta.getValue("name").jsonPrimitive.content.length <= 1024 && meta.getValue("mime").jsonPrimitive.content.length <= 256)
    require(meta.getValue("recipe").jsonPrimitive.content.startsWith(if (v3) "CVPOS3 byte layout v3." else "CVPOS2 byte layout v2."))
    val header = fixed + jsonBytes
    val h = MessageDigest.getInstance("SHA-256").digest(header)
    val prefix = MessageDigest.getInstance("SHA-256").apply { update(header) }
    val original = MessageDigest.getInstance("SHA-256")
    var seq = 0L; var position = 0L; var short = false
    while (true) {
        val record = exact(24)
        val b = ByteBuffer.wrap(record)
        val kind = String(ByteArray(4).also(b::get), Charsets.US_ASCII)
        require(b.long == seq && b.long == position)
        val length = b.int
        if (kind == "DATA") require(seq < 4294967294L && !short && length in 1..chunk)
        else require(kind == "DONE" && length == 64)
        val encrypted = exact(length + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce + ByteBuffer.allocate(8).putLong(seq).array()))
        cipher.updateAAD(h + record)
        val plaintext = cipher.doFinal(encrypted)
        if (kind == "DATA") {
            prefix.update(record); prefix.update(encrypted); original.update(plaintext)
            output.write(plaintext); position = Math.addExact(position, length.toLong()); seq++; short = length < chunk
        } else {
            val hash = original.digest()
            require(MessageDigest.isEqual(hash, plaintext.copyOfRange(0, 32)) && MessageDigest.isEqual(prefix.digest(), plaintext.copyOfRange(32, 64)))
            require(input.read() == -1)
            return DecodeResult(position, seq, hash.joinToString("") { "%02x".format(it) }, dates)
        }
    }
}

private fun checkFlatUniqueFields(text: String) {
    var inString = false; var escaped = false; var expectingKey = false; var level = 0; var start = -1
    val seen = mutableSetOf<String>()
    for ((i, c) in text.withIndex()) {
        if (inString) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') { inString = false; if (start >= 0) {
                require(seen.add(Json.parseToJsonElement(text.substring(start, i + 1)).jsonPrimitive.content)); start = -1
            } }
        } else when (c) {
            '"' -> { inString = true; if (expectingKey) start = i }
            '{' -> { level++; require(level == 1); expectingKey = true }
            '}' -> level--
            '[', ']' -> error("Nested bootstrap")
            ':' -> expectingKey = false
            ',' -> expectingKey = true
        }
    }
    require(!inString && level == 0)
}
