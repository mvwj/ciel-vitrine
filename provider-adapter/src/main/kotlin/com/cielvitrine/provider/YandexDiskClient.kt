package com.cielvitrine.provider

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Additive REST foundation. No destructive operations and no unproven capability claims. */
class YandexDiskClient internal constructor(
    private val token: () -> String,
    private val api: HttpUrl,
    private val http: OkHttpClient,
) {
    constructor(token: () -> String) : this(token, "https://cloud-api.yandex.net/v1/disk/".toHttpUrl(),
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build())

    data class Resource(
        val path: String, val objectId: String?, val byteLength: Long?, val sha256: String?, val modified: String?,
    )
    data class Observation(val presence: ProviderObjectPresence, val resource: Resource? = null)
    data class UploadReceipt(val accepted: Boolean, val needsReconciliation: Boolean)

    fun inspect(path: String): Observation {
        val request = apiRequest("resources", mapOf("path" to path, "fields" to "path,resource_id,size,sha256,modified,type"))
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return Observation(ProviderObjectPresence.ACCESS_DENIED)
            // A missing path may be an external rename. It never proves deletion of a known object.
            if (response.code == 404) return Observation(ProviderObjectPresence.NOT_AT_EXPECTED_PATH)
            if (!response.isSuccessful) return Observation(ProviderObjectPresence.UNKNOWN)
            val json = boundedJson(response.body ?: error("Missing metadata"))
            if (json["type"]?.jsonPrimitive?.content != "file") return Observation(ProviderObjectPresence.UNKNOWN)
            val resource = Resource(json["path"]?.jsonPrimitive?.content ?: path,
                json["resource_id"]?.jsonPrimitive?.contentOrNull,
                json["size"]?.jsonPrimitive?.longOrNull,
                json["sha256"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) },
                json["modified"]?.jsonPrimitive?.contentOrNull)
            return Observation(ProviderObjectPresence.PRESENT, resource)
        }
    }

    /** Never overwrites another original. 202 is not treated as committed/verified. */
    fun uploadNew(path: String, byteLength: Long, openSource: () -> InputStream): UploadReceipt {
        require(byteLength >= 0)
        val link = signedLink("resources/upload", path, "PUT", mapOf("overwrite" to "false"))
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = byteLength
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                openSource().use { source ->
                    var copied = 0L
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        check(count > 0 && count <= byteLength - copied) { "Source length changed" }
                        sink.write(buffer, 0, count)
                        copied += count
                    }
                    check(copied == byteLength) { "Incomplete source" }
                }
            }
        }
        // The upload URL carries its own temporary authority. OAuth stays on the API host.
        http.newCall(Request.Builder().url(link).put(body).build()).execute().use { response ->
            return when (response.code) {
                201 -> UploadReceipt(true, false)
                202 -> UploadReceipt(true, true)
                else -> error("Yandex upload failed (HTTP ${response.code}); reconcile before retry")
            }
        }
    }

    /** Caller must digest/recover the entire stream before accepting an exact-copy proof. */
    fun <T> fullReadback(path: String, read: (InputStream) -> T): T {
        val link = signedLink("resources/download", path, "GET")
        http.newCall(Request.Builder().url(link).header("Authorization", authorization()).get().build()).execute().use { response ->
            check(response.code == 200) { "Download unavailable (HTTP ${response.code})" }
            return (response.body ?: error("Empty download response")).byteStream().use(read)
        }
    }

    private fun signedLink(endpoint: String, path: String, method: String, extras: Map<String, String> = emptyMap()): HttpUrl {
        http.newCall(apiRequest(endpoint, mapOf("path" to path) + extras)).execute().use { response ->
            check(response.code == 200) { "Link request failed (HTTP ${response.code})" }
            val json = boundedJson(response.body ?: error("Missing link"))
            check(json["method"]?.jsonPrimitive?.content == method && json["templated"]?.jsonPrimitive?.booleanOrNull == false)
            val url = json["href"]!!.jsonPrimitive.content.toHttpUrl()
            check(isYandexTransferUrl(url)) { "Untrusted transfer host/scheme" }
            return url
        }
    }

    private fun authorization(): String {
        val value = token()
        require(value.isNotBlank() && '\r' !in value && '\n' !in value)
        return "OAuth $value"
    }
    private fun apiRequest(endpoint: String, args: Map<String, String>): Request {
        val url = api.newBuilder().addPathSegments(endpoint).apply { args.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return Request.Builder().url(url).header("Authorization", authorization()).get().build()
    }
    private fun boundedJson(body: okhttp3.ResponseBody): JsonObject {
        val source = body.source()
        source.request(1_048_577)
        check(source.buffer.size <= 1_048_576) { "Oversized JSON response" }
        return Json.parseToJsonElement(body.string()).jsonObject
    }

    companion object {
        internal fun isYandexTransferUrl(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            listOf("yandex.net", "yandex.ru", "yandex.com").any { url.host == it || url.host.endsWith(".$it") }
    }
}
