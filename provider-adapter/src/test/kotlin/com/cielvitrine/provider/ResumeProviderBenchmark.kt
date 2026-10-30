package com.cielvitrine.provider

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResumeProviderBenchmark {
    @Test fun transientStreamingRangeResponsesAreIoFailuresWithoutReplayingTheBody() {
        for(status in listOf(429,503))MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"href":"https://downloader.dst.yandex.ru/file","method":"GET","templated":false}"""))
            var calls=0
            val http=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                val request=chain.request()
                if(request.url.host==server.url("/").host)return@addInterceptor chain.proceed(request)
                calls++;assertEquals("bytes=4096-8191",request.header("Range"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(ByteArray(0).toResponseBody()).build()
            }.build()
            val client=YandexDiskClient({"test-token"},server.url("/v1/disk/"),http)
            assertThrows(java.io.IOException::class.java) { client.openRange("app:/fixture.bin",4096,8192).close() }
            assertEquals(1,calls)
        }
    }
    @Test fun R08P_actualClientValidatesPartialResponses() {
        for (status in listOf(206, 200, 416)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"href":"https://downloader.dst.yandex.ru/file","method":"GET","templated":false}"""))
            var calls = 0
            val http = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == server.url("/").host) return@addInterceptor chain.proceed(request)
                calls++; assertEquals("GET", request.method)
                assertEquals("bytes=4096-8191", request.header("Range"))
                assertEquals("identity", request.header("Accept-Encoding"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .header("Content-Range", "bytes 4096-8191/10000").body(ByteArray(4096) { 77 }.toResponseBody()).build()
            }.build()
            val client = YandexDiskClient({ "test-token" }, server.url("/v1/disk/"), http)
            if (status == 206) assertArrayEquals(ByteArray(4096) { 77 }, client.rangeReadback("app:/fixture.bin", 4096, 4096, 10000))
            else assertThrows(Exception::class.java) { client.rangeReadback("app:/fixture.bin", 4096, 4096, 10000) }
            assertEquals(1, calls); assertEquals(1, server.requestCount)
        }
    }
}
