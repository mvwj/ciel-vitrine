package com.cielvitrine.provider

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class YandexDiskClientTest {
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
