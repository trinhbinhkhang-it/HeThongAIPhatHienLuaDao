package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Zalo Bot channel: the token gate, the `sendMessage` body and the
 * message wording. The Android sender ([ZaloNotifier]) needs a real socket and is not
 * exercised here.
 */
class ZaloNotifierUnitTest {

    // ── Bot token gate ──

    @Test
    fun wellFormedTokens_areAccepted() {
        assertTrue(isValidZaloBotToken("123456789:abc123xyz"))
        assertTrue(isValidZaloBotToken("123456789:abcDEF-123_xyz"))
        // Whitespace from a paste is tolerated.
        assertTrue(isValidZaloBotToken("  123456789:abc123xyz  "))
    }

    @Test
    fun malformedTokens_areRejected() {
        assertFalse(isValidZaloBotToken(""))
        assertFalse(isValidZaloBotToken("   "))
        assertFalse("no colon", isValidZaloBotToken("123456789abc123xyz"))
        assertFalse("empty secret", isValidZaloBotToken("123456789:"))
        assertFalse("id too short", isValidZaloBotToken("1234:abcde"))
        assertFalse("secret too short", isValidZaloBotToken("123456789:abc"))
        assertFalse("non-numeric id", isValidZaloBotToken("abcdefghi:abcde"))
    }

    // ── sendMessage body ──

    @Test
    fun sendPayload_carriesChatIdAndText() {
        val json = zaloSendPayload("abc.xyz", "Xin chào bạn")
        assertTrue(json.contains("\"chat_id\":\"abc.xyz\""))
        assertTrue(json.contains("\"text\":\"Xin chào bạn\""))
    }

    @Test
    fun sendPayload_escapesHostileInput() {
        val json = zaloSendPayload("a\"b", "line\n\"quote\"")
        assertTrue(json.contains("a\\\"b"))
        assertTrue(json.contains("line\\n\\\"quote\\\""))
    }

    @Test
    fun sendPayload_truncatesToTheZaloLimit() {
        val longText = "x".repeat(2500)
        val json = zaloSendPayload("abc", longText)
        // 2000 x's survive, the rest is dropped.
        assertTrue(json.contains("x".repeat(2000)))
        assertFalse(json.contains("x".repeat(2001)))
    }

    // ── Message wording ──

    @Test
    fun alertMessage_containsTheSharedMetadata() {
        val text = zaloAlertMessage(
            userName = "Nguyễn Văn A",
            label = ALERT_RISK_LABEL,
            timestampMs = 1_700_000_000_000L,
            device = "Google Pixel 7 (Android 14)"
        )
        assertTrue(text.contains("Phát hiện dấu hiệu thao túng khuôn mặt trong cuộc gọi."))
        assertTrue(text.contains("Tên người dùng: Nguyễn Văn A"))
        assertTrue(text.contains("Nhãn: $ALERT_RISK_LABEL"))
        assertTrue(text.contains("Thiết bị: Google Pixel 7 (Android 14)"))
    }

    @Test
    fun alertMessage_reportsAPlaceholderForABlankName() {
        val text = zaloAlertMessage("  ", ALERT_RISK_LABEL, 1_700_000_000_000L, "Pixel")
        assertTrue(text.contains("Tên người dùng: —"))
    }

    // ── chat_id extraction (getUpdates / webhook) ──

    @Test
    fun extractChatId_readsTheNestedChatId() {
        val body = """
            {"ok":true,"result":{"message":{"from":{"id":"from-123","display_name":"Ted"},
            "chat":{"id":"chat-abc","chat_type":"PRIVATE"},"text":"Xin chào",
            "message_id":"m1","date":1750316131602},"event_name":"message.text.received"}}
        """.trimIndent()
        assertEquals("chat-abc", zaloExtractChatId(body))
    }

    /** Only `chat.id` is the reply target — `from.id` must not be mistaken for it. */
    @Test
    fun extractChatId_ignoresTheSenderId() {
        val body = """{"ok":true,"result":{"from":{"id":"sender-only"}}}"""
        assertEquals(null, zaloExtractChatId(body))
    }

    @Test
    fun extractChatId_returnsNullWhenThereIsNoMessage() {
        assertEquals(null, zaloExtractChatId("{\"ok\":true,\"result\":null}"))
        assertEquals(null, zaloExtractChatId(""))
    }

    // ── Full chat extraction (id + type + name) ──

    @Test
    fun extractChat_readsIdTypeAndSenderName() {
        val body = """
            {"ok":true,"result":{"message":{"from":{"id":"f1","display_name":"Ted"},
            "chat":{"id":"c1","chat_type":"PRIVATE"},"text":"hi"}}}
        """.trimIndent()
        val chat = zaloExtractChat(body)!!
        assertEquals("c1", chat.chatId)
        assertFalse(chat.isGroup)
        assertEquals("Ted", chat.name)
    }

    @Test
    fun extractChat_marksGroupsAndFallsBackToIdForName() {
        val body = """{"ok":true,"result":{"message":{"chat":{"id":"g1","chat_type":"GROUP"}}}}"""
        val chat = zaloExtractChat(body)!!
        assertEquals("g1", chat.chatId)
        assertTrue(chat.isGroup)
        assertEquals("g1", chat.name)
    }

    // ── Recipient list (de)serialisation ──

    @Test
    fun recipients_roundTrip() {
        val list = listOf(
            ZaloRecipient("c1", "Ted", isGroup = false),
            ZaloRecipient("g1", "Team", isGroup = true)
        )
        val parsed = parseZaloRecipients(serializeZaloRecipients(list))
        assertEquals(list, parsed)
    }

    @Test
    fun recipients_parseDropsMalformedAndDeduplicates() {
        val raw = "c1|PRIVATE|Ted\n\nbrokenline\n|c2|X\nc1|PRIVATE|Ted2"
        val parsed = parseZaloRecipients(raw)
        assertEquals(1, parsed.size)
        assertEquals("c1", parsed[0].chatId)
        assertEquals("Ted", parsed[0].label)
    }

    @Test
    fun recipients_serialiseSanitisesLabel() {
        val serialized = serializeZaloRecipients(listOf(ZaloRecipient("c1", "a|b\nc")))
        // The dangerous characters are stripped; the list still parses back cleanly.
        assertEquals("c1|PRIVATE|a/b c", serialized)
        assertEquals("a/b c", parseZaloRecipients(serialized)[0].label)
    }

    @Test
    fun recipients_addReplacesExistingById() {
        val list = listOf(ZaloRecipient("c1", "Old"))
        val updated = addZaloRecipient(list, ZaloRecipient("c1", "New", isGroup = true))
        assertEquals(1, updated.size)
        assertEquals("New", updated[0].label)
        assertTrue(updated[0].isGroup)
    }

    @Test
    fun recipients_removeDropsById() {
        val list = listOf(ZaloRecipient("c1", "A"), ZaloRecipient("c2", "B"))
        assertEquals(listOf("c2"), removeZaloRecipient(list, "c1").map { it.chatId })
    }

    @Test
    fun recipients_emptyForBlankInput() {
        assertTrue(parseZaloRecipients(null).isEmpty())
        assertTrue(parseZaloRecipients("").isEmpty())
        assertTrue(parseZaloRecipients("   ").isEmpty())
    }
}
