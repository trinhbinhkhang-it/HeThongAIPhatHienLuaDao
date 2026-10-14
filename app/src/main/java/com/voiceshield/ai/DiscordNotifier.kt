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
 * The service resolves the device string and the user name into this shape at the moment the
 * alert fires.
 *
 * The face rectangle, the scan mode and the risk score are deliberately *not* carried: the
 * embed reports the result and who/where it fired for, not the geometry or score behind it.
 *
 * @param userName the name from the personal-information page, or blank when the user set none.
 */
data class DiscordAlert(
    val userName: String,
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

/** The single risk label every alert reports, per the product wording. */
const val ALERT_RISK_LABEL = "Nguy cơ lừa đảo qua cuộc gọi video"

/** The attachment filename the embed references via `attachment://`. */
const val DISCORD_SCREENSHOT_FILENAME = "screenshot.jpg"

/**
 * Builds the Discord webhook body: a single rich embed. The fields are exactly the ones the
 * product asked for — user name, risk score, label, time and device — plus an optional
 * screenshot reference. The scan mode and the face rectangle are deliberately omitted.
 *
 * @param isTest marks the payload the "Test" button sends, so a colleague can tell a probe
 *   from a real alert.
 * @param hasImage when true the embed points at the attached screenshot so Discord renders it
 *   inline.
 */
fun discordEmbedPayload(
    alert: DiscordAlert,
    isTest: Boolean = false,
    hasImage: Boolean = false
): String {
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
    val name = alert.userName.ifBlank { "—" }

    fun field(name: String, value: String, inline: Boolean): String =
        "{\"name\":\"${escapeJson(name)}\",\"value\":\"${escapeJson(value)}\"," +
            "\"inline\":$inline}"

    val fields = buildList {
        add(field("Tên người dùng", name, false))
        add(field("Nhãn", ALERT_RISK_LABEL, false))
        add(field("Thời gian", timeLocal, true))
        add(field("Thiết bị", alert.device, false))
        if (hasImage) add(field("Ảnh chụp màn hình", "Đính kèm bên dưới", false))
    }.joinToString(",")

    return buildString {
        append("{\"embeds\":[{")
        append("\"title\":\"${escapeJson(title)}\",")
        append("\"description\":\"${escapeJson(description)}\",")
        append("\"color\":$DISCORD_EMBED_COLOR,")
        append("\"timestamp\":\"${escapeJson(timeIso)}\",")
        append("\"fields\":[$fields],")
        if (hasImage) {
            append("\"image\":{\"url\":\"attachment://$DISCORD_SCREENSHOT_FILENAME\"},")
        }
        append("\"footer\":{\"text\":\"SafeCallsCT\"}")
        append("}]}")
    }
}

/**
 * Assembles a `multipart/form-data` body for Discord: the JSON payload goes in a
 * `payload_json` part and the screenshot in a `files[0]` part, which is what lets a webhook
 * post carry an image with no hosting and no dependency. Top-level and Android-free so the
 * boundary framing is unit-testable.
 *
 * @param boundary a unique token; the caller sets the same value in the request's
 *   `Content-Type` header.
 */
fun multipartBody(
    boundary: String,
    payloadJson: String,
    imageBytes: ByteArray,
    filename: String = DISCORD_SCREENSHOT_FILENAME
): ByteArray {
    val crlf = "\r\n"
    val head = buildString {
        append("--$boundary$crlf")
        append("Content-Disposition: form-data; name=\"payload_json\"$crlf")
        append("Content-Type: application/json$crlf$crlf")
        append(payloadJson)
        append(crlf)
        append("--$boundary$crlf")
        append("Content-Disposition: form-data; name=\"files[0]\"; filename=\"$filename\"$crlf")
        append("Content-Type: image/jpeg$crlf$crlf")
    }.toByteArray(Charsets.UTF_8)
    val tail = "$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8)

    val out = ByteArray(head.size + imageBytes.size + tail.size)
    System.arraycopy(head, 0, out, 0, head.size)
    System.arraycopy(imageBytes, 0, out, head.size, imageBytes.size)
    System.arraycopy(tail, 0, out, head.size + imageBytes.size, tail.size)
    return out
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

    /**
     * @param imageBytes when non-null, the screenshot is attached as `files[0]` via
     *   `multipart/form-data`; the embed must already reference it (`hasImage = true`).
     * @param onResult `(success, httpCode)`; `httpCode` is -1 when the request never landed.
     */
    fun send(
        webhookUrl: String,
        payloadJson: String,
        imageBytes: ByteArray? = null,
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
                    connection.setRequestProperty("User-Agent", "SafeCallsCT")
                    if (imageBytes != null) {
                        val boundary = "----SafeCallsCTBoundary${System.currentTimeMillis()}"
                        connection.setRequestProperty(
                            "Content-Type",
                            "multipart/form-data; boundary=$boundary"
                        )
                        val body = multipartBody(boundary, payloadJson, imageBytes)
                        connection.setFixedLengthStreamingMode(body.size)
                        connection.outputStream.use { it.write(body) }
                    } else {
                        connection.setRequestProperty(
                            "Content-Type",
                            "application/json; charset=utf-8"
                        )
                        connection.outputStream.use {
                            it.write(payloadJson.toByteArray(Charsets.UTF_8))
                        }
                    }
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
