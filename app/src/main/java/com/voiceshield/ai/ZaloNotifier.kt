package com.voiceshield.ai

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Zalo Bot Platform API base. All calls are `POST {base}/bot<token>/<method>`. */
const val ZALO_API_BASE = "https://bot-api.zaloplatforms.com"

/**
 * A Zalo bot token looks like `<numeric id>:<secret>` (e.g. `123456789:abc123xyz`). Checked
 * before any request so an obvious typo cannot turn into a stray outbound call.
 */
private val ZALO_TOKEN_REGEX = Regex("^\\d{5,}:[A-Za-z0-9_-]{5,}$")

/** Pure validation for the developer-entered bot token. Android-free, so it is JVM-testable. */
fun isValidZaloBotToken(token: String): Boolean = ZALO_TOKEN_REGEX.matches(token.trim())

/**
 * Builds the `sendMessage` JSON body. Zalo caps `text` at 2000 characters, so it is truncated
 * rather than rejected — a slightly shortened alert is better than none.
 */
fun zaloSendPayload(chatId: String, text: String): String =
    "{\"chat_id\":\"${escapeJson(chatId.trim())}\",\"text\":\"${escapeJson(text.take(2000))}\"}"

/**
 * The plain-text message a high-risk result sends to Zalo — the same metadata the Discord
 * embed carries (who, what label, when, on what device), laid out for a chat bubble.
 *
 * Top-level and Android-free (java.time only) so the wording is unit-testable, exactly like
 * [discordEmbedPayload].
 */
fun zaloAlertMessage(
    userName: String,
    label: String,
    timestampMs: Long,
    device: String
): String {
    val timeLocal = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestampMs))
    val name = userName.ifBlank { "—" }
    return buildString {
        append("⚠️ Cảnh báo deepfake\n\n")
        append("Phát hiện dấu hiệu thao túng khuôn mặt trong cuộc gọi.\n\n")
        append("Tên người dùng: $name\n")
        append("Nhãn: $label\n")
        append("Thời gian: $timeLocal\n")
        append("Thiết bị: $device")
    }
}

/**
 * Sends a text message through the Zalo Bot Platform — no third-party dependency.
 *
 * Mirrors [DiscordNotifier]: the request runs off the caller's thread and the result is
 * handed back on the main looper. Zalo returns HTTP 200 with `{"ok":false,...}` on a rejected
 * message, so success is `2xx` **and** `"ok":true` in the body.
 */
object ZaloNotifier {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zalo-bot").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** @param onResult `(success, httpCode)`; `httpCode` is -1 when the request never landed. */
    fun send(
        token: String,
        chatId: String,
        text: String,
        onResult: (Boolean, Int) -> Unit = { _, _ -> }
    ) {
        executor.execute {
            var code = -1
            var ok = false
            try {
                val url = URL("$ZALO_API_BASE/bot${token.trim()}/sendMessage")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.useCaches = false
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("User-Agent", "SafeCallsCT")
                    val body = zaloSendPayload(chatId, text).toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                    code = connection.responseCode
                    val responseBody = runCatching {
                        (if (code in 200..299) connection.inputStream else connection.errorStream)
                            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    ok = code in 200..299 && Regex("\"ok\"\\s*:\\s*true").containsMatchIn(responseBody)
                } finally {
                    connection.disconnect()
                }
            } catch (t: Throwable) {
                ok = false
            }
            val finalOk = ok
            val finalCode = code
            mainHandler.post { onResult(finalOk, finalCode) }
        }
    }
}
