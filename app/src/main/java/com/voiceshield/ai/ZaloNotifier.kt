package com.voiceshield.ai

import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * Extracts `result.message.chat.id` from a `getUpdates`/webhook JSON body.
 *
 * Deliberately a tolerant regex rather than a parser: the response nests the chat under
 * `message.chat` exactly like the webhook sample, but a single object and an array of updates
 * are both plausible shapes, and this matches the first `"chat": { ... "id": ... }` either way
 * without pulling in a JSON dependency. Returns null when the body carries no message yet
 * (the user has not written to the bot, or a webhook is configured instead).
 */
fun zaloExtractChatId(responseBody: String): String? =
    Regex("\"chat\"\\s*:\\s*\\{[^}]*?\"id\"\\s*:\\s*\"([^\"]+)\"")
        .find(responseBody)
        ?.groupValues
        ?.getOrNull(1)

/** A chat a Zalo bot can message: a person (PRIVATE) or a group (GROUP, Beta). */
data class ZaloChat(
    val chatId: String,
    val isGroup: Boolean,
    /** Sender's display name, used as a friendly label; falls back to the id. */
    val name: String
)

/**
 * One configured recipient of the Zalo alert. [isGroup] is only for display — a group is
 * delivered to exactly like a person, via its `chat.id`.
 */
data class ZaloRecipient(
    val chatId: String,
    val label: String,
    val isGroup: Boolean = false
)

/**
 * Extracts the full chat from a `getUpdates`/webhook body: the id, whether it is a group, and
 * the sender's display name. Tolerant regexes for the same reason as [zaloExtractChatId] —
 * the response shape follows the webhook sample but the platform may return a single object or
 * an array, and no JSON dependency is pulled in.
 */
fun zaloExtractChat(responseBody: String): ZaloChat? {
    val id = zaloExtractChatId(responseBody) ?: return null
    val type = Regex("\"chat_type\"\\s*:\\s*\"([^\"]+)\"")
        .find(responseBody)?.groupValues?.getOrNull(1)
    val name = Regex("\"display_name\"\\s*:\\s*\"([^\"]*)\"")
        .find(responseBody)?.groupValues?.getOrNull(1)
    return ZaloChat(
        chatId = id,
        isGroup = type.equals("GROUP", ignoreCase = true),
        name = name?.takeIf { it.isNotBlank() } ?: id
    )
}

// ── Recipient list (de)serialisation — pure so the storage format is unit-tested ──
// One recipient per line: `<chatId>|GROUP|PRIVATE|<label>`. The label is last so it may contain
// almost anything; it is stripped of `|` and newlines on write so the format can never corrupt.

/** Parses the stored recipient list, dropping malformed lines and de-duplicating by chat id. */
fun parseZaloRecipients(raw: String?): List<ZaloRecipient> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size < 2) return@mapNotNull null
            val id = parts[0].trim()
            if (id.isEmpty()) return@mapNotNull null
            val label = parts.getOrNull(2)?.trim().orEmpty().ifEmpty { id }
            ZaloRecipient(id, label, parts[1].trim().equals("GROUP", ignoreCase = true))
        }
        .distinctBy { it.chatId }
        .toList()
}

/** Serialises the recipient list for storage. */
fun serializeZaloRecipients(recipients: List<ZaloRecipient>): String =
    recipients.joinToString("\n") { r ->
        val safeLabel = r.label.replace('|', '/').replace('\n', ' ').replace('\r', ' ')
        "${r.chatId}|${if (r.isGroup) "GROUP" else "PRIVATE"}|$safeLabel"
    }

/** Adds [recipient], replacing any existing entry with the same chat id (de-duplicating). */
fun addZaloRecipient(
    recipients: List<ZaloRecipient>,
    recipient: ZaloRecipient
): List<ZaloRecipient> = recipients.filterNot { it.chatId == recipient.chatId } + recipient

/** Removes the recipient with [chatId], if present. */
fun removeZaloRecipient(
    recipients: List<ZaloRecipient>,
    chatId: String
): List<ZaloRecipient> = recipients.filterNot { it.chatId == chatId }



/**
 * Sends a text message through the Zalo Bot Platform — no third-party dependency.
 *
 * Mirrors [DiscordNotifier]: the request runs off the caller's thread and the result is
 * handed back on the main looper. Zalo returns HTTP 200 with `{"ok":false,...}` on a rejected
 * message, so success is `2xx` **and** `"ok":true` in the body.
 */
object ZaloNotifier {
    private const val TAG = "SafeCallsCT-Zalo"

    /** Server-side long-poll window per `getUpdates` attempt, in seconds (must be > 0). */
    private const val POLL_SECONDS = 5

    /** How many polls the chat-id helper makes before giving up (~attempts × POLL_SECONDS). */
    private const val DEFAULT_POLL_ATTEMPTS = 4

    /** Pause between consecutive sends in [sendAll], to stay clear of Zalo's rate limit. */
    private const val SEND_PACING_MS = 350L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zalo-bot").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fetches the `chat_id` of the most recent message sent to the bot, for the "Lấy Chat ID"
     * helper.
     *
     * `getUpdates` is a long-poll endpoint and it **consumes** updates, so this polls for a short
     * window ([attempts] × [POLL_SECONDS]) while the developer sends the bot a fresh message.
     * Two things learned against the live API drive the shape:
     *  - `timeout:"0"` is treated as *unset* and falls back to the 30 s server default, so a small
     *    positive value is sent instead and the client read timeout is kept above it;
     *  - a poll that finds nothing returns `{"ok":false,"error_code":408}` after waiting, which is
     *    "no message yet", not a failure.
     *
     * Returns null with `httpCode == -1` only when the request never landed at all.
     */
    fun fetchLatestChat(
        token: String,
        attempts: Int = DEFAULT_POLL_ATTEMPTS,
        onResult: (chat: ZaloChat?, httpCode: Int) -> Unit = { _, _ -> }
    ) {
        executor.execute {
            var code = -1
            var chat: ZaloChat? = null
            var i = 0
            while (chat == null && i < attempts) {
                val (found, pollCode) = pollForChatOnce(token)
                code = pollCode
                chat = found
                i++
            }
            val finalChat = chat
            val finalCode = code
            mainHandler.post { onResult(finalChat, finalCode) }
        }
    }

    /**
     * One `getUpdates` call. Returns `(chat, httpCode)`; `chat` is null when no message arrived
     * within the poll window.
     */
    private fun pollForChatOnce(token: String): Pair<ZaloChat?, Int> {
        var code = -1
        var chat: ZaloChat? = null
        try {
            val url = URL("$ZALO_API_BASE/bot${token.trim()}/getUpdates")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                // Comfortably above the server-side poll, so we read the answer rather than cut it.
                connection.readTimeout = (POLL_SECONDS + 8) * 1_000
                connection.useCaches = false
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("User-Agent", "SafeCallsCT")
                // A small *positive* timeout is honoured; "0" falls back to the 30 s default.
                val body = "{\"timeout\":\"$POLL_SECONDS\"}".toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                code = connection.responseCode
                val responseBody = runCatching {
                    (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull().orEmpty()
                chat = zaloExtractChat(responseBody)
                Log.i(TAG, "getUpdates -> HTTP $code chatFound=${chat != null}")
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getUpdates failed", t)
            chat = null
        }
        return chat to code
    }

    /** @param onResult `(success, httpCode)`; `httpCode` is -1 when the request never landed. */
    fun send(
        token: String,
        chatId: String,
        text: String,
        onResult: (Boolean, Int) -> Unit = { _, _ -> }
    ) {
        executor.execute {
            val (ok, code) = sendMessageOnce(token, chatId, text)
            mainHandler.post { onResult(ok, code) }
        }
    }

    /**
     * Sends [text] to every recipient in turn — one bot token, many chats. A short pause between
     * sends keeps a burst from tripping Zalo's rate limit. Reports how many landed so the UI can
     * say "2/3".
     */
    fun sendAll(
        token: String,
        recipients: List<ZaloRecipient>,
        text: String,
        onResult: (sent: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        executor.execute {
            var sent = 0
            recipients.forEachIndexed { index, recipient ->
                if (index > 0) runCatching { Thread.sleep(SEND_PACING_MS) }
                val (ok, _) = sendMessageOnce(token, recipient.chatId, text)
                if (ok) sent++
            }
            val finalSent = sent
            val total = recipients.size
            mainHandler.post { onResult(finalSent, total) }
        }
    }

    /** Blocking single `sendMessage`. Returns `(ok, httpCode)`; runs on the executor thread. */
    private fun sendMessageOnce(token: String, chatId: String, text: String): Pair<Boolean, Int> {
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
                Log.i(TAG, "sendMessage -> HTTP $code ok=$ok")
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendMessage failed", t)
            ok = false
        }
        return ok to code
    }
}
