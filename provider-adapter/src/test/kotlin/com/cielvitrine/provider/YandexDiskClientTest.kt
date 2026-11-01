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
    @Test fun `range transient failures get two retries and never replay the link or a PUT`() {
        for (recover in listOf(false,true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"href":"https://downloader.dst.yandex.ru/file","method":"GET","templated":false}"""))
            var requests=0
            val http=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                val request=chain.request()
                if(request.url.host==server.url("/").host)return@addInterceptor chain.proceed(request)
                assertEquals("GET",request.method);assertEquals("bytes=100-103",request.header("Range"))
                requests++
                val status=if(recover && requests==3)206 else if(requests==1)429 else 503
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .header("Content-Range","bytes 100-103/1000").body(byteArrayOf(1,2,3,4).toResponseBody()).build()
            }.build()
            val client=YandexDiskClient({"test-token"},server.url("/v1/disk/"),http)
            if(recover)assertArrayEquals(byteArrayOf(1,2,3,4),client.rangeReadback("app:/pack.bin",100,4,1000))
            else assertThrows(IOException::class.java){client.rangeReadback("app:/pack.bin",100,4,1000)}
            assertEquals(3,requests);assertEquals(1,server.requestCount)
        }
    }
    @Test fun `range transfer returns only exact bytes and retains range across a trusted redirect without OAuth`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"href":"https://downloader.dst.yandex.ru/file","method":"GET","templated":false}"""))
            var calls=0
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                val req=chain.request()
                if(req.url.host==server.url("/").host)return@addInterceptor chain.proceed(req)
                assertEquals("bytes=100-103",req.header("Range"));assertEquals("identity",req.header("Accept-Encoding"));calls++
                val response=Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                if(calls==1){assertEquals("OAuth test-token",req.header("Authorization"));response.code(302).message("Redirect").header("Location","https://storage.yandex.net/file").body(ByteArray(0).toResponseBody()).build()}
                else {assertNull(req.header("Authorization"));response.code(206).message("Partial").header("Content-Range","bytes 100-103/1000").body(byteArrayOf(1,2,3,4).toResponseBody()).build()}
            }.build()
            assertArrayEquals(byteArrayOf(1,2,3,4),YandexDiskClient({"test-token"},server.url("/v1/disk/"),http).rangeReadback("app:/pack.bin",100,4,1000))
            assertEquals(2,calls)
        }
    }
    @Test fun `range rejects full responses wrong offsets truncated oversized and encoded bodies`() {
        for(case in 0..4)MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"href":"https://downloader.dst.yandex.ru/file","method":"GET","templated":false}"""))
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                val req=chain.request();if(req.url.host==server.url("/").host)return@addInterceptor chain.proceed(req)
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if(case==0)200 else 206).message("Response")
                    .header("Content-Range",if(case==1)"bytes 101-104/1000" else "bytes 100-103/1000")
                    .apply{if(case==4)header("Content-Encoding","gzip")}
                    .body(ByteArray(if(case==2)3 else if(case==3)5 else 4).toResponseBody()).build()
            }.build()
            assertThrows(Exception::class.java){YandexDiskClient({"test-token"},server.url("/v1/disk/"),http).rangeReadback("app:/pack.bin",100,4,1000)}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun `invalid range is rejected before any credential or network access`() {
        var used=false
        val client=YandexDiskClient({used=true;"test-token"})
        listOf(Triple(-1L,4,1000L),Triple(999L,4,1000L),Triple(0L,0,1L),Triple(Long.MAX_VALUE,4,Long.MAX_VALUE)).forEach{(s,n,t)->
            assertThrows(IllegalArgumentException::class.java){client.rangeReadback("app:/pack.bin",s,n,t)}
        };assertFalse(used)
    }
    @Test fun `metadata body failure retries the same GET once on a fresh connection`() {
        MockWebServer().use { server ->
            // A single loopback address keeps DNS route fallback from consuming the deliberate retry.
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val body = """{"type":"file","path":"app:/original.bin","resource_id":"object","size":25,"modified":"v1"}"""
            server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody(body))
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/").newBuilder().host("127.0.0.1").build(), OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            assertEquals("object", client.inspect("app:/original.bin").resource!!.objectId)
            val requests = List(2) { server.takeRequest() }
            assertTrue(requests.all { it.method == "GET" && it.getHeader("Authorization") == "OAuth test-token" })
            assertEquals(requests[0].path, requests[1].path)
            assertEquals(0, requests[1].sequenceNumber)
        }
    }
    @Test fun `persistent metadata failure stops after three GETs`() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            repeat(3) { server.enqueue(MockResponse().setBody("{}      ").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)) }
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/").newBuilder().host("127.0.0.1").build(), OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            assertThrows(IOException::class.java) { client.inspect("app:/original.bin") }
            assertEquals(3, server.requestCount)
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
            val uploaded=client.uploadNew("app:/test.cv2", original.size.toLong()) { original.inputStream() }
            assertTrue(uploaded.accepted)
            assertEquals(original.size.toLong(),uploaded.timings!!.bodyBytes)
            assertTrue(uploaded.timings!!.linkNanos>=0 && uploaded.timings!!.bodyNanos>=0 && uploaded.timings!!.requestNanos>=uploaded.timings!!.bodyNanos)
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
                repeat(if(code == 503)3 else 1) { server.enqueue(MockResponse().setResponseCode(code)) }
                assertEquals(expected, client.inspect("app:/Холодный архив/a.pos").presence)
                repeat(if(code == 503)3 else 1) {
                    val request = server.takeRequest()
                    assertEquals("app:/Холодный архив/a.pos", request.requestUrl!!.queryParameter("path"))
                    assertEquals("OAuth test-token", request.getHeader("Authorization"))
                }
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
