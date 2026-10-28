package com.qingyu.inputmethod

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class HandwritingUploadClientTest {

    private val sample = requireNotNull(
        HandwritingSampleFormat.encode(
            "永",
            HandwritingSampleDraft(
                100,
                100,
                listOf(listOf(HandwritingPoint(10, 10, 0), HandwritingPoint(20, 20, 10))),
            ),
            "6f1c2a9e-7b1d-4c55-9a2e-1d4b6c8e9f01",
            "2026-10-20",
        )
    )

    @Test
    fun reportsSuccessAndSendsJsonlBody() {
        var receivedBody = ""
        var receivedType = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange ->
            receivedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            receivedType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val outcome = HandwritingUploadClient.upload(
                "http://127.0.0.1:${server.address.port}/ok",
                HandwritingUploadPlanner.Batch(listOf(sample), 1, sample.length),
            )
            assertEquals(HandwritingUploadClient.Outcome.Success, outcome)
            assertEquals(sample + "\n", receivedBody)
            assertTrue(receivedType.startsWith("application/x-ndjson"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun classifiesClientAndServerErrors() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bad") { exchange ->
            exchange.sendResponseHeaders(400, -1)
            exchange.close()
        }
        server.createContext("/retry") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val batch = HandwritingUploadPlanner.Batch(listOf(sample), 1, sample.length)
            val bad = HandwritingUploadClient.upload(
                "http://127.0.0.1:${server.address.port}/bad",
                batch,
            )
            assertTrue(bad is HandwritingUploadClient.Outcome.Permanent)
            val retry = HandwritingUploadClient.upload(
                "http://127.0.0.1:${server.address.port}/retry",
                batch,
            )
            assertTrue(retry is HandwritingUploadClient.Outcome.Retryable)
        } finally {
            server.stop(0)
        }
    }
}
