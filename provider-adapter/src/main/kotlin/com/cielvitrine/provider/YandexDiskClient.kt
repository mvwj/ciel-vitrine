package com.cielvitrine.provider

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Additive REST foundation. No destructive operations and no unproven capability claims. */
class YandexDiskClient internal constructor(
    private val token: () -> String,
    private val api: HttpUrl,
    private val http: OkHttpClient,
    private val identityApi: HttpUrl = "https://login.yandex.ru/info".toHttpUrl(),
) {
    constructor(token: () -> String) : this(token, "https://cloud-api.yandex.net/v1/disk/".toHttpUrl(),
        OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.MINUTES).build())

    data class Resource(
        val path: String, val objectId: String?, val byteLength: Long?, val sha256: String?, val modified: String?,
    )
    data class Observation(val presence: ProviderObjectPresence, val resource: Resource? = null)
    data class UploadTimings(val linkNanos:Long,val requestNanos:Long,val bodyNanos:Long,val bodyBytes:Long,val phase:String="complete",val responseCode:Int?=null)
    data class UploadReceipt(val accepted: Boolean, val needsReconciliation: Boolean,val timings:UploadTimings?=null)

    data class Account(val uid: String, val label: String, val totalBytes: Long, val usedBytes: Long, val appRootId: String?)
    enum class ConnectionProblem { UNAUTHORIZED, DISK_PERMISSION, APP_FOLDER_PERMISSION, APP_FOLDER_UNAVAILABLE, IDENTITY_MISMATCH, UNAVAILABLE }
    class ConnectionException(val problem: ConnectionProblem) : Exception(problem.name)

    /** Read-only probes. Never creates folders or uploads files during sign-in. */
    fun connectionInfo(expectedClientId: String): Account {
        val identityRequest = Request.Builder().url(identityApi.newBuilder().addQueryParameter("format", "json").build())
            .header("Authorization", authorization()).get().build()
        val identity = readMetadata(identityRequest) { response ->
            if (response.code == 401 || response.code == 403) throw ConnectionException(ConnectionProblem.UNAUTHORIZED)
            if (response.code != 200) throw ConnectionException(ConnectionProblem.UNAVAILABLE)
            boundedJson(response.body ?: throw ConnectionException(ConnectionProblem.UNAVAILABLE))
        }
        val uid = identity["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[0-9]{1,32}")) }
            ?: throw ConnectionException(ConnectionProblem.IDENTITY_MISMATCH)
        if (identity["client_id"]?.jsonPrimitive?.contentOrNull != expectedClientId) throw ConnectionException(ConnectionProblem.IDENTITY_MISMATCH)
        val disk = readMetadata(apiRequest("", mapOf("fields" to "total_space,used_space,user.uid"))) { response ->
            if (response.code == 401) throw ConnectionException(ConnectionProblem.UNAUTHORIZED)
            if (response.code == 403) throw ConnectionException(ConnectionProblem.DISK_PERMISSION)
            if (response.code != 200) throw ConnectionException(ConnectionProblem.UNAVAILABLE)
            boundedJson(response.body ?: throw ConnectionException(ConnectionProblem.UNAVAILABLE))
        }
        val diskUid = (disk["user"] as? JsonObject)?.get("uid")?.jsonPrimitive?.contentOrNull
        if (diskUid != null && diskUid != uid) throw ConnectionException(ConnectionProblem.IDENTITY_MISMATCH)
        val total = disk["total_space"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
            ?: throw ConnectionException(ConnectionProblem.UNAVAILABLE)
        val used = disk["used_space"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
            ?: throw ConnectionException(ConnectionProblem.UNAVAILABLE)
        val root = readMetadata(apiRequest("resources", mapOf("path" to "app:/", "fields" to "type,path,resource_id"))) { response ->
            if (response.code == 401) throw ConnectionException(ConnectionProblem.UNAUTHORIZED)
            if (response.code == 403) throw ConnectionException(ConnectionProblem.APP_FOLDER_PERMISSION)
            if (response.code == 404) throw ConnectionException(ConnectionProblem.APP_FOLDER_UNAVAILABLE)
            if (response.code != 200) throw ConnectionException(ConnectionProblem.UNAVAILABLE)
            boundedJson(response.body ?: throw ConnectionException(ConnectionProblem.UNAVAILABLE))
        }
        if (root["type"]?.jsonPrimitive?.contentOrNull != "dir") throw ConnectionException(ConnectionProblem.APP_FOLDER_UNAVAILABLE)
        val label = identity["display_name"]?.jsonPrimitive?.contentOrNull ?: identity["login"]?.jsonPrimitive?.contentOrNull ?: uid
        return Account(uid, label.take(120), total, used, root["resource_id"]?.jsonPrimitive?.contentOrNull)
    }

    fun inspect(path: String): Observation {
        val request = apiRequest("resources", mapOf("path" to path, "fields" to "path,resource_id,size,sha256,modified,type"))
        readMetadata(request) { response ->
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

    /** Additive directory creation. A 409 is accepted only after observing a directory. */
    fun ensureDirectory(path: String) {
        require(path.startsWith("app:/") && !path.contains(".."))
        val request = apiRequest("resources", mapOf("path" to path)).newBuilder()
            .put(ByteArray(0).toRequestBody()).build()
        val code = http.newCall(request).execute().use { it.code }
        if (code == 201) return
        check(code == 409) { "Directory creation unavailable" }
        readMetadata(apiRequest("resources", mapOf("path" to path, "fields" to "type"))) { response ->
            check(response.code == 200 && boundedJson(response.body ?: error("Missing metadata"))["type"]?.jsonPrimitive?.content == "dir")
        }
    }

    /** Never overwrites another original. 202 is not treated as committed/verified. */
    fun uploadNew(path: String, byteLength: Long, observe:(UploadTimings,Boolean)->Unit={_,_->},openSource: () -> InputStream): UploadReceipt {
        require(byteLength >= 0)
        val linkStarted=System.nanoTime()
        val link = signedLink("resources/upload", path, "PUT", mapOf("overwrite" to "false"))
        val linkNanos=System.nanoTime()-linkStarted
        var bodyNanos=0L;var bodyBytes=0L;var phase="connect";var code:Int?=null
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = byteLength
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val bodyStarted=System.nanoTime()
                try {
                openSource().use { source ->
                    var copied = 0L
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        check(count > 0 && count <= byteLength - copied) { "Source length changed" }
                        sink.write(buffer, 0, count)
                        copied += count
                        bodyBytes=copied
                    }
                    check(copied == byteLength) { "Incomplete source" }
                }
                }finally{bodyNanos=System.nanoTime()-bodyStarted}
            }
        }
        // The upload URL carries its own temporary authority. OAuth stays on the API host.
        val requestStarted=System.nanoTime()
        val listener=object:okhttp3.EventListener() {
            override fun dnsStart(call:okhttp3.Call,domainName:String){phase="dns"}
            override fun connectStart(call:okhttp3.Call,inetSocketAddress:java.net.InetSocketAddress,proxy:java.net.Proxy){phase="connect"}
            override fun secureConnectStart(call:okhttp3.Call){phase="tls"}
            override fun requestHeadersStart(call:okhttp3.Call){phase="request_headers"}
            override fun requestBodyStart(call:okhttp3.Call){phase="request_body"}
            override fun requestBodyEnd(call:okhttp3.Call,byteCount:Long){phase="server_response"}
            override fun responseHeadersStart(call:okhttp3.Call){phase="response_headers"}
        }
        var success=false
        try {http.newBuilder().readTimeout(180,TimeUnit.SECONDS).eventListener(listener).build().newCall(Request.Builder().url(link).put(body).build()).execute().use { response ->
            code=response.code;success=code==201 || code==202
            val timing=UploadTimings(linkNanos,System.nanoTime()-requestStarted,bodyNanos,bodyBytes,phase,code)
            return when (response.code) {
                201 -> UploadReceipt(true, false,timing)
                202 -> UploadReceipt(true, true,timing)
                else -> error("Yandex upload failed (HTTP ${response.code}); reconcile before retry")
            }
        }}finally{runCatching{observe(UploadTimings(linkNanos,System.nanoTime()-requestStarted,bodyNanos,bodyBytes,phase,code),success)}}
    }

    /** Caller must digest/recover the entire stream before accepting an exact-copy proof. */
    fun <T> fullReadback(path: String, read: (InputStream) -> T): T {
        var link = signedLink("resources/download", path, "GET")
        val visited = mutableSetOf<HttpUrl>()
        val downloads = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        var sendAuthorization = true
        repeat(6) {
            check(isYandexTransferUrl(link) && visited.add(link)) { "Untrusted or looping download redirect" }
            // Yandex documents OAuth on the initial download. Drop it on an origin change.
            val request = Request.Builder().url(link).get().apply {
                if (sendAuthorization) header("Authorization", authorization())
            }.build()
            downloads.newCall(request).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let(link::resolve) ?: error("Missing download redirect")
                    check(isYandexTransferUrl(next)) { "Untrusted download redirect" }
                    if (next.host != link.host) sendAuthorization = false
                    link = next
                } else {
                    check(response.code == 200) { "Download unavailable (HTTP ${response.code})" }
                    return (response.body ?: error("Empty download response")).byteStream().use(read)
                }
            }
        }
        error("Too many download redirects")
    }

    /** Strict partial transfer. A server ignoring Range is rejected before consuming its body. */
    fun openRange(path: String, start: Long, total: Long): InputStream {
        require(start >= 0 && start < total)
        val end = total - 1
        var link = signedLink("resources/download", path, "GET")
        val visited = mutableSetOf<HttpUrl>()
        var sendAuthorization = true
        val downloads = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        repeat(6) {
            check(isYandexTransferUrl(link) && visited.add(link)) { "Untrusted or looping download redirect" }
            val request = Request.Builder().url(link).get().header("Range", "bytes=$start-$end")
                .header("Accept-Encoding", "identity").apply {
                    if (sendAuthorization) header("Authorization", authorization())
                }.build()
            val call = downloads.newCall(request)
            call.timeout().timeout(75, TimeUnit.SECONDS)
            val response = call.execute()
            try {
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let(link::resolve) ?: error("Missing download redirect")
                    check(isYandexTransferUrl(next)) { "Untrusted download redirect" }
                    if (next.host != link.host) sendAuthorization = false
                    link = next
                    call.cancel(); response.close()
                } else {
                    val body = checkedRangeBody(response, start, end, total)
                    return object : java.io.FilterInputStream(body.byteStream()) {
                        private var remaining = total - start
                        override fun read(): Int {
                            val byte = ByteArray(1)
                            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
                        }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                            if (length == 0) return 0
                            val n = `in`.read(bytes, offset, minOf(length.toLong(), remaining + 1).toInt())
                            if (n < 0 && remaining != 0L) throw IOException("Truncated range")
                            check(n < 0 || n > 0 && n <= remaining) { "Oversized or empty range read" }
                            if (n > 0) remaining -= n
                            return n
                        }
                        override fun close() {
                            // Abort before close, so an invalid/abandoned response is never drained
                            // just to make its connection reusable. The caller owns this response.
                            call.cancel(); response.close()
                        }
                    }
                }
            } catch (error: Throwable) {
                call.cancel(); response.close(); throw error
            }
        }
        error("Too many download redirects")
    }

    /** Bounded tile read. Retry uses fresh private arrays; multipart progress uses openRange. */
    fun rangeReadback(path: String, start: Long, length: Int, total: Long): ByteArray {
        require(start >= 0 && length in 1..8_388_636 && start < total && length.toLong() <= total - start)
        val end = start + length - 1
        var link = signedLink("resources/download", path, "GET")
        val visited = mutableSetOf<HttpUrl>()
        var sendAuthorization = true
        val downloads = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        repeat(6) {
            check(isYandexTransferUrl(link) && visited.add(link)) { "Untrusted or looping download redirect" }
            val request = Request.Builder().url(link).get().header("Range", "bytes=$start-$end")
                .header("Accept-Encoding", "identity").apply {
                    if (sendAuthorization) header("Authorization", authorization())
                }.build()
            readRange(request, downloads) { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let(link::resolve) ?: error("Missing download redirect")
                    check(isYandexTransferUrl(next)) { "Untrusted download redirect" }
                    if (next.host != link.host) sendAuthorization = false
                    link = next
                } else {
                    val body = checkedRangeBody(response, start, end, total)
                    return body.byteStream().use { input ->
                        val bytes = ByteArray(length); var done = 0
                        while (done < length) {
                            val n = input.read(bytes, done, length - done)
                            check(n > 0) { "Truncated range" }; done += n
                        }
                        check(input.read() == -1) { "Oversized range" }; bytes
                    }
                }
            }
        }
        error("Too many download redirects")
    }

    private fun checkedRangeBody(response: Response, start: Long, end: Long, total: Long): okhttp3.ResponseBody {
        if (response.code == 429 || response.code in 500..599) throw IOException("Transient range response")
        check(response.code == 206) { "Partial download unavailable (HTTP ${response.code})" }
        check(response.header("Content-Range") == "bytes $start-$end/$total") { "Incorrect range response" }
        check(response.header("Content-Encoding").let { it == null || it == "identity" })
        val body = response.body ?: error("Missing range body")
        check(body.contentLength() == -1L || body.contentLength() == end - start + 1) { "Incorrect range length" }
        return body
    }

    private fun signedLink(endpoint: String, path: String, method: String, extras: Map<String, String> = emptyMap()): HttpUrl {
        readMetadata(apiRequest(endpoint, mapOf("path" to path) + extras)) { response ->
            check(response.code == 200) { "Link request failed (HTTP ${response.code})" }
            val json = boundedJson(response.body ?: error("Missing link"))
            check(json["method"]?.jsonPrimitive?.content == method && json["templated"]?.jsonPrimitive?.booleanOrNull == false)
            val url = json["href"]!!.jsonPrimitive.content.toHttpUrl()
            check(isYandexTransferUrl(url)) { "Untrusted transfer host/scheme" }
            return url
        }
    }

    /** Retry only bounded metadata GETs, including a failed body read. Never replay a PUT
     * or a download callback that may already have written plaintext to its destination. */
    private inline fun <T> readMetadata(request: Request, read: (Response) -> T): T {
        require(request.method == "GET")
        repeat(3) { attempt ->
            try {
                val call = http.newCall(request)
                call.timeout().timeout(75, TimeUnit.SECONDS)
                return call.execute().use { response ->
                    if (attempt < 2 && (response.code == 429 || response.code in 500..599)) throw IOException("Transient metadata response")
                    read(response)
                }
            } catch (e: IOException) {
                if (attempt == 2 || e is java.io.InterruptedIOException && Thread.currentThread().isInterrupted) throw e
                try { Thread.sleep(100L shl attempt) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("Metadata read cancelled") }
            }
        }
        error("Metadata retry exhausted")
    }

    /** Range destinations are private arrays, created afresh for each attempt. A PUT or a full
     * readback callback can have side effects and is deliberately excluded from this retry path. */
    private inline fun <T> readRange(request: Request, client: OkHttpClient, read: (Response) -> T): T {
        repeat(3) { attempt ->
            try {
                val call = client.newCall(request)
                call.timeout().timeout(75, TimeUnit.SECONDS)
                return call.execute().use { response ->
                    if (response.code == 429 || response.code in 500..599) throw IOException("Transient range response")
                    read(response)
                }
            } catch (error: IOException) {
                if (attempt == 2 || Thread.currentThread().isInterrupted) throw error
                try { Thread.sleep(100L shl attempt) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("Range cancelled") }
            }
        }
        error("Range retry exhausted")
    }

    private fun authorization(): String {
        val value = token()
        require(value.isNotBlank() && '\r' !in value && '\n' !in value)
        return "OAuth $value"
    }
    private fun apiRequest(endpoint: String, args: Map<String, String>): Request {
        val builder = api.newBuilder()
        if (endpoint.isEmpty() && api.pathSegments.last() == "") builder.removePathSegment(api.pathSegments.lastIndex)
        else builder.addPathSegments(endpoint)
        val url = builder.apply { args.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
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
