package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Discord alert mirror: the developer-entered webhook URL gate, the
 * JSON escaping, and the embed body itself.
 *
 * All three are top-level Android-free functions (same convention as [sanitizeInterval] and
 * [isTripleClick]), so they are testable without Robolectric or an emulator. The Android
 * sender ([DiscordNotifier]) is deliberately *not* exercised here — it needs a real socket.
 */
class DiscordWebhookUnitTest {

    // ── Webhook URL gate ──

    @Test
    fun wellFormedDiscordWebhooks_areAccepted() {
        assertTrue(
            isValidDiscordWebhook("https://discord.com/api/webhooks/123456789/abcDEF-token")
        )
        assertTrue(
            isValidDiscordWebhook("https://discordapp.com/api/webhooks/123/abc")
        )
        assertTrue(
            isValidDiscordWebhook("https://ptb.discord.com/api/webhooks/123/abc")
        )
        // Surrounding whitespace from a paste must not fail the check.
        assertTrue(
            isValidDiscordWebhook("  https://discord.com/api/webhooks/123/abc  ")
        )
    }

    @Test
    fun nonDiscordOrInsecureUrls_areRejected() {
        assertFalse(isValidDiscordWebhook(""))
        assertFalse(isValidDiscordWebhook("   "))
        assertFalse("cleartext must be refused", isValidDiscordWebhook("http://discord.com/api/webhooks/1/a"))
        assertFalse(isValidDiscordWebhook("https://evil.example.com/api/webhooks/1/a"))
        assertFalse("host suffix trick", isValidDiscordWebhook("https://notdiscord.com/api/webhooks/1/a"))
        assertFalse("missing webhook path", isValidDiscordWebhook("https://discord.com/"))
        assertFalse(isValidDiscordWebhook("not a url at all"))
    }

    // ── JSON escaping ──

    @Test
    fun escapeJson_neutralisesQuotesBackslashesAndControls() {
        assertEquals("he said \\\"hi\\\"", escapeJson("he said \"hi\""))
        assertEquals("a\\\\b", escapeJson("a\\b"))
        assertEquals("line1\\nline2", escapeJson("line1\nline2"))
        assertEquals("tab\\tend", escapeJson("tab\tend"))
        // A device string with no special characters passes through untouched.
        assertEquals("Samsung SM-G991B", escapeJson("Samsung SM-G991B"))
    }

    // ── Embed body ──

    private fun sampleAlert() = DiscordAlert(
        score = 84,
        riskLabel = "RỦI RO CAO",
        modeLabel = "Chế độ Cảnh Báo",
        faceLeft = 120,
        faceTop = 240,
        faceWidth = 320,
        faceHeight = 320,
        timestampMs = 1_700_000_000_000L,
        device = "Google Pixel 7 (Android 14)"
    )

    @Test
    fun payload_isAnEmbedCarryingEveryMetadataField() {
        val json = discordEmbedPayload(sampleAlert())
        assertTrue("must be a single embed", json.startsWith("{\"embeds\":[{"))
        assertTrue(json.contains("\"color\":$DISCORD_EMBED_COLOR"))
        assertTrue("risk score", json.contains("84%"))
        assertTrue("mode", json.contains("Chế độ Cảnh Báo"))
        assertTrue("risk label", json.contains("RỦI RO CAO"))
        assertTrue("face rect", json.contains("x=120, y=240, w=320, h=320"))
        assertTrue("device", json.contains("Google Pixel 7 (Android 14)"))
        assertTrue("footer", json.contains("SafeCallsCT"))
    }

    @Test
    fun missingFace_reportsNoFaceInsteadOfAFakeRectangle() {
        val noFace = sampleAlert().copy(
            faceLeft = null, faceTop = null, faceWidth = null, faceHeight = null
        )
        assertTrue(discordEmbedPayload(noFace).contains("không có khuôn mặt trong phiên"))
    }

    @Test
    fun testPayload_isDistinguishableFromARealAlert() {
        val real = discordEmbedPayload(sampleAlert(), isTest = false)
        val probe = discordEmbedPayload(sampleAlert(), isTest = true)
        assertTrue(real.contains("Cảnh báo deepfake"))
        assertTrue("a probe must not read as a real alert", !probe.contains("Cảnh báo deepfake"))
        assertTrue(probe.contains("TEST"))
    }

    /** A hostile device name cannot break out of its JSON string. */
    @Test
    fun payload_escapesHostileMetadata() {
        val json = discordEmbedPayload(sampleAlert().copy(device = "X\"},{\"name\":\"evil"))
        assertFalse("the injected key must not appear unescaped", json.contains("\"name\":\"evil\""))
        assertTrue(json.contains("X\\\"},{\\\"name\\\":\\\"evil"))
    }
}
