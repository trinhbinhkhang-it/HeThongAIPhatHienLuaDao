package com.voiceshield.ai

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * The metadata a Warning-mode high-risk result hands to Discord.
 *
 * Deliberately a plain data holder of primitives only — no [android.graphics.Rect], no
 * [android.os.Build] — so [discordEmbedPayload] stays a top-level Android-free function and
 * can be pinned by a JVM unit test, exactly like [sanitizeInterval] and [scanDelayMs].
 * The service resolves the face rectangle and the device string into this shape at the
 * moment the alert fires.
 *
 * @param faceLeft/faceTop/faceWidth/faceHeight the saved session face in screen pixels, all
 *   null together when the session held no face.
 */
data class DiscordAlert(
    val score: Int,
    val riskLabel: String,
    val modeLabel: String,
    val faceLeft: Int?,
    val faceTop: Int?,
    val faceWidth: Int?,
    val faceHeight: Int?,
    val timestampMs: Long,
    val device: String
)

/** Hosts a Discord webhook may legitimately live on. */
private val DISCORD_WEBHOOK_HOSTS =
    listOf("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com")

/**
 * Pure validation for the developer-entered webhook URL.
 *
 * Requires HTTPS, a known Discord host, and the `/api/webhooks/...` path — the shape
 * Discord actually serves. Anything else is rejected before a request is ever built, so a
 * typo cannot turn into an outbound POST to an arbitrary host. Android-free, so it is
 * testable on the local JVM.
 */
fun isValidDiscordWebhook(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true)) return false
    return runCatching {
        val parsed = URL(trimmed)
        val host = parsed.host?.lowercase().orEmpty()
        val hostOk = DISCORD_WEBHOOK_HOSTS.any { host == it || host.endsWith(".$it") }
        hostOk && parsed.path.orEmpty().startsWith("/api/webhooks/")
    }.getOrDefault(false)
}

/**
 * Escapes [value] for safe embedding inside a JSON string literal.
 *
 * The payload is assembled by hand — no JSON dependency is added — so this is what keeps a
 * device name containing a quote or a newline from producing invalid JSON, and what closes
 * the JSON-injection hole that naive string concatenation would open.
 */
fun escapeJson(value: String): String {
    val sb = StringBuilder(value.length + 16)
    for (c in value) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

/** Terracotta (#C2553A) — the app's high-risk accent, in the decimal Discord wants. */
const val DISCORD_EMBED_COLOR = 0xC2553A

/**
 * Builds the Discord webhook body: a single rich embed carrying **metadata only** — risk
 * score, mode, face rectangle, timestamp and device. No image, no frame, nothing the
 * capture path produced beyond the numbers already on the bubble.
 *
 * @param isTest marks the payload the "Test" button sends, so a colleague can tell a probe
 *   from a real alert.
 */
fun discordEmbedPayload(alert: DiscordAlert, isTest: Boolean = false): String {
    val face = if (
        alert.faceLeft != null && alert.faceTop != null &&
        alert.faceWidth != null && alert.faceHeight != null
    ) {
        "x=${alert.faceLeft}, y=${alert.faceTop}, w=${alert.faceWidth}, h=${alert.faceHeight}"
    } else {
        "không có khuôn mặt trong phiên"
    }
    val instant = Instant.ofEpochMilli(alert.timestampMs)
    val timeIso = instant.toString()
    val timeLocal = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(instant)
    val title = if (isTest) "🧪 TEST — SafeCallsCT" else "⚠️ Cảnh báo deepfake"
    val description = if (isTest) {
        "Tin nhắn thử nghiệm từ cấu hình ẩn SafeCallsCT."
    } else {
        "Phát hiện dấu hiệu thao túng khuôn mặt trong cuộc gọi."
    }

    fun field(name: String, value: String, inline: Boolean): String =
        "{\"name\":\"${escapeJson(name)}\",\"value\":\"${escapeJson(value)}\"," +
            "\"inline\":$inline}"

    val fields = listOf(
        field("Điểm rủi ro", "${alert.score}%", true),
        field("Chế độ", alert.modeLabel, true),
        field("Nhãn", alert.riskLabel, true),
        field("Vùng khuôn mặt", face, false),
        field("Thời gian", timeLocal, true),
        field("Thiết bị", alert.device, false)
    ).joinToString(",")

    return buildString {
        append("{\"embeds\":[{")
        append("\"title\":\"${escapeJson(title)}\",")
        append("\"description\":\"${escapeJson(description)}\",")
        append("\"color\":$DISCORD_EMBED_COLOR,")
        append("\"timestamp\":\"${escapeJson(timeIso)}\",")
        append("\"fields\":[$fields],")
        append("\"footer\":{\"text\":\"SafeCallsCT\"}")
        append("}]}")
    }
}

/**
 * Posts a webhook payload with the platform HTTP client — no third-party dependency.
 *
 * The request runs on a single-thread executor so it can never touch the caller's thread
 * (the service calls this from the main thread, where a network write would raise
 * `NetworkOnMainThreadException`), and the result is handed back on the main looper so a
 * UI toast can be raised directly from the callback.
 */
object DiscordNotifier {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "discord-webhook").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** @param onResult `(success, httpCode)`; `httpCode` is -1 when the request never landed. */
    fun send(
        webhookUrl: String,
        payloadJson: String,
        onResult: (Boolean, Int) -> Unit = { _, _ -> }
    ) {
        executor.execute {
            var code = -1
            var ok = false
            try {
                val connection = URL(webhookUrl.trim()).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.useCaches = false
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("User-Agent", "SafeCallsCT")
                    connection.outputStream.use { it.write(payloadJson.toByteArray(Charsets.UTF_8)) }
                    code = connection.responseCode
                    ok = code in 200..299
                    runCatching {
                        (if (ok) connection.inputStream else connection.errorStream)?.close()
                    }
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
