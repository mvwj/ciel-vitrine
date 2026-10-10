package com.cielvitrine.provider

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class YandexDiskClientTest {
    @Test fun `metadata body failure retries the same GET once on a fresh connection`() {
        MockWebServer().use { server ->
            val body = """{"type":"file","path":"app:/original.bin","resource_id":"object","size":25,"modified":"v1"}"""
            server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody(body))
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            assertEquals("object", client.inspect("app:/original.bin").resource!!.objectId)
            val requests = List(2) { server.takeRequest() }
            assertTrue(requests.all { it.method == "GET" && it.getHeader("Authorization") == "OAuth test-token" })
            assertEquals(requests[0].path, requests[1].path)
            assertEquals(0, requests[1].sequenceNumber)
        }
    }
    @Test fun `persistent metadata failure stops after two GETs`() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("{}      ").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)) }
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            assertThrows(IOException::class.java) { client.inspect("app:/original.bin") }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `metadata recovery never repeats a failed directory PUT`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(201))
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            assertThrows(IOException::class.java) { client.ensureDirectory("app:/test") }
            assertEquals(1, server.requestCount)
            assertEquals("PUT", server.takeRequest().method)
        }
    }
    @Test fun `signed transfer writes exact bytes and reads full body with initial download authorization`() {
        val original = ByteArray(131079) { (it * 31).toByte() }
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host != "downloader.dst.yandex.ru") return@addInterceptor chain.proceed(request)
                if (request.method == "PUT") {
                    assertNull(request.header("Authorization"))
                    assertTrue(request.body!!.isOneShot())
                    assertEquals(original.size.toLong(), request.body!!.contentLength())
                    val buffer = Buffer(); request.body!!.writeTo(buffer)
                    assertArrayEquals(original, buffer.readByteArray())
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(201).message("Created").body(ByteArray(0).toResponseBody()).build()
                } else {
                    assertEquals("OAuth test-token", request.header("Authorization"))
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(original.toResponseBody()).build()
                }
            }.build()
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), http)
            server.enqueue(MockResponse().setBody("""{"href":"https://downloader.dst.yandex.ru/upload","method":"PUT","templated":false}"""))
            assertTrue(client.uploadNew("app:/test.cv2", original.size.toLong()) { original.inputStream() }.accepted)
            assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("overwrite"))
            server.enqueue(MockResponse().setBody("""{"href":"https://downloader.dst.yandex.ru/download","method":"GET","templated":false}"""))
            assertArrayEquals(original, client.fullReadback("app:/test.cv2") { it.readBytes() })
        }
    }
    @Test fun `download follows validated relative and cross-host redirects and calls the reader once`() {
        MockWebServer().use { server ->
            val seen = mutableListOf<String>(); var reads = 0
            val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == server.hostName) return@addInterceptor chain.proceed(request)
                if (request.url.host == "downloader.dst.yandex.ru") assertEquals("OAuth test-token", request.header("Authorization"))
                else assertNull(request.header("Authorization"))
                assertEquals("GET", request.method)
                seen += request.url.toString()
                val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Test")
                when (request.url.encodedPath) {
                    "/start" -> response.code(302).header("Location", "/next").body(ByteArray(0).toResponseBody()).build()
                    "/next" -> response.code(307).header("Location", "https://storage.dst.yandex.net/final").body(ByteArray(0).toResponseBody()).build()
                    else -> response.code(200).body(byteArrayOf(1, 2, 3).toResponseBody()).build()
                }
            }.build()
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), http)
            server.enqueue(MockResponse().setBody("""{"href":"https://downloader.dst.yandex.ru/start","method":"GET","templated":false}"""))
            val result = client.fullReadback("app:/original.bin") { reads++; it.readBytes() }
            assertArrayEquals(byteArrayOf(1, 2, 3), result); assertEquals(1, reads); assertEquals(3, seen.size)
        }
    }
    @Test fun `untrusted downgraded and looping download redirects never invoke the reader`() {
        listOf("https://outside.example/file", "http://downloader.dst.yandex.ru/file", "https://downloader.dst.yandex.ru/start").forEach { target ->
            MockWebServer().use { server ->
                var transfers = 0
                val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.host == server.hostName) return@addInterceptor chain.proceed(request)
                    transfers++; assertEquals("OAuth test-token", request.header("Authorization"))
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                        .header("Location", target).body(ByteArray(0).toResponseBody()).build()
                }.build()
                server.enqueue(MockResponse().setBody("""{"href":"https://downloader.dst.yandex.ru/start","method":"GET","templated":false}"""))
                val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), http)
                assertThrows(IllegalStateException::class.java) { client.fullReadback("app:/original.bin") { throw AssertionError("Reader invoked before a verified transfer") } }
                assertEquals(1, transfers)
            }
        }
    }
    @Test fun `folder conflict is accepted only for an observed directory`() {
        MockWebServer().use { server ->
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient())
            server.enqueue(MockResponse().setResponseCode(201))
            client.ensureDirectory("app:/ciel-vitrine-v2")
            val created = server.takeRequest()
            assertEquals("PUT", created.method)
            assertEquals("app:/ciel-vitrine-v2", created.requestUrl!!.queryParameter("path"))
            server.enqueue(MockResponse().setResponseCode(409))
            server.enqueue(MockResponse().setBody("""{"type":"file"}"""))
            assertThrows(IllegalStateException::class.java) { client.ensureDirectory("app:/ciel-vitrine-v2") }
            server.enqueue(MockResponse().setResponseCode(409))
            server.enqueue(MockResponse().setBody("""{"type":"dir"}"""))
            client.ensureDirectory("app:/ciel-vitrine-v2")
        }
    }
    @Test fun `connection requires client identity disk information and application folder`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"123","client_id":"public-client","login":"test-user"}"""))
            server.enqueue(MockResponse().setBody("""{"total_space":1000,"used_space":100,"user":{"uid":"123"}}"""))
            server.enqueue(MockResponse().setBody("""{"type":"dir","path":"disk:/Applications/Test","resource_id":"root-object"}"""))
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient(), server.url("/info"))
            val info = client.connectionInfo("public-client")
            assertEquals("123", info.uid)
            assertEquals("test-user", info.label)
            assertEquals(1000L, info.totalBytes)
            assertEquals("root-object", info.appRootId)
            val requests = List(3) { server.takeRequest() }
            assertTrue(requests.all { it.method == "GET" && it.getHeader("Authorization") == "OAuth test-token" })
            assertEquals("json", requests[0].requestUrl!!.queryParameter("format"))
            assertEquals("/v1/disk", requests[1].requestUrl!!.encodedPath)
            assertEquals("app:/", requests[2].requestUrl!!.queryParameter("path"))
        }
    }
    @Test fun `wrong OAuth client cannot proceed to Disk`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"123","client_id":"other-app"}"""))
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient(), server.url("/info"))
            val failure = assertThrows(YandexDiskClient.ConnectionException::class.java) { client.connectionInfo("public-client") }
            assertEquals(YandexDiskClient.ConnectionProblem.IDENTITY_MISMATCH, failure.problem)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `identity success does not bypass missing Disk rights`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"123","client_id":"public-client"}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient(), server.url("/info"))
            val failure = assertThrows(YandexDiskClient.ConnectionException::class.java) { client.connectionInfo("public-client") }
            assertEquals(YandexDiskClient.ConnectionProblem.DISK_PERMISSION, failure.problem)
        }
    }
    @Test fun `different Disk UID and denied app folder cannot be connected`() {
        listOf(true, false).forEach { wrongUid -> MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"123","client_id":"public-client"}"""))
            server.enqueue(MockResponse().setBody("""{"total_space":1000,"used_space":100,"user":{"uid":"${if (wrongUid) "456" else "123"}"}}"""))
            if (!wrongUid) server.enqueue(MockResponse().setResponseCode(403))
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient(), server.url("/info"))
            val failure = assertThrows(YandexDiskClient.ConnectionException::class.java) { client.connectionInfo("public-client") }
            assertEquals(if (wrongUid) YandexDiskClient.ConnectionProblem.IDENTITY_MISMATCH else YandexDiskClient.ConnectionProblem.APP_FOLDER_PERMISSION, failure.problem)
        } }
    }
    @Test fun `missing path access denial and unavailable service stay distinct`() {
        MockWebServer().use { server ->
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient())
            listOf(404 to ProviderObjectPresence.NOT_AT_EXPECTED_PATH, 401 to ProviderObjectPresence.ACCESS_DENIED, 403 to ProviderObjectPresence.ACCESS_DENIED, 503 to ProviderObjectPresence.UNKNOWN).forEach { (code, expected) ->
                server.enqueue(MockResponse().setResponseCode(code))
                assertEquals(expected, client.inspect("app:/Холодный архив/a.pos").presence)
                val request = server.takeRequest()
                assertEquals("app:/Холодный архив/a.pos", request.requestUrl!!.queryParameter("path"))
                assertEquals("OAuth test-token", request.getHeader("Authorization"))
            }
        }
    }
    @Test fun `metadata hash is optional and not fabricated from md5`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"path":"app:/a.pos","resource_id":"object","size":123,"type":"file","md5":"ignored"}"""))
            val result = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient()).inspect("app:/a.pos")
            assertEquals(123L, result.resource!!.byteLength)
            assertNull(result.resource.sha256)
        }
    }
    @Test fun `transfer authority cannot escape to lookalike hosts or cleartext`() {
        listOf("http://downloader.dst.yandex.ru/a", "https://yandex.ru.evil.example/a", "https://user:password@yandex.net/a", "https://yandex.net:8443/a").forEach { assertFalse(YandexDiskClient.isYandexTransferUrl(it.toHttpUrl())) }
        assertTrue(YandexDiskClient.isYandexTransferUrl("https://downloader.dst.yandex.ru/a".toHttpUrl()))
    }
    @Test fun `upload request forbids overwrite and rejects untrusted signed link`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"href":"https://evil.example/a","method":"PUT","templated":false}"""))
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), OkHttpClient())
            assertThrows(IllegalStateException::class.java) { client.uploadNew("app:/a.pos", 0) { byteArrayOf().inputStream() } }
            assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("overwrite"))
        }
    }
}
