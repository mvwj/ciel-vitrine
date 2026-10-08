package com.cielvitrine.provider

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class YandexDiskClientTest {
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
