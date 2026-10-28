package com.qingyu.inputmethod

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 自动上传的 HTTP 客户端；不记录请求体，也不解析服务端返回的样本内容。 */
object HandwritingUploadClient {

    sealed class Outcome {
        object Success : Outcome()
        data class Retryable(val message: String) : Outcome()
        data class Permanent(val message: String) : Outcome()
    }

    fun upload(endpoint: String, batch: HandwritingUploadPlanner.Batch): Outcome {
        if (batch.lines.isEmpty()) return Outcome.Success
        val body = batch.lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/x-ndjson; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "QingyuIME-Handwriting/1")
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.use { input ->
                val buffer = ByteArray(512)
                input.read(buffer)
            }
            when {
                code in 200..299 -> Outcome.Success
                code == 408 || code == 425 || code == 429 -> Outcome.Retryable("HTTP $code")
                code in 500..599 -> Outcome.Retryable("HTTP $code")
                else -> Outcome.Permanent("HTTP $code")
            }
        } catch (e: IllegalArgumentException) {
            Outcome.Permanent("地址无效")
        } catch (e: IOException) {
            Outcome.Retryable("网络不可用")
        } finally {
            connection?.disconnect()
        }
    }
}
