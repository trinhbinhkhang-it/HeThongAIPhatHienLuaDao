package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Discord alert mirror: the developer-entered webhook URL gate, the
 * JSON escaping, the embed body, the multipart upload framing and the high-risk score roll.
 *
 * All of these are top-level Android-free functions (same convention as [sanitizeInterval] and
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
        userName = "Nguyễn Văn A",
        timestampMs = 1_700_000_000_000L,
        device = "Google Pixel 7 (Android 14)"
    )

    @Test
    fun payload_isAnEmbedCarryingEveryMetadataField() {
        val json = discordEmbedPayload(sampleAlert())
        assertTrue("must be a single embed", json.startsWith("{\"embeds\":[{"))
        assertTrue(json.contains("\"color\":$DISCORD_EMBED_COLOR"))
        assertTrue("user name field", json.contains("Tên người dùng"))
        assertTrue("user name value", json.contains("Nguyễn Văn A"))
        assertTrue("risk label", json.contains("Nguy cơ lừa đảo qua cuộc gọi video"))
        assertTrue("device", json.contains("Google Pixel 7 (Android 14)"))
        assertTrue("footer", json.contains("SafeCallsCT"))
    }

    /** The product asked for the score, the mode and the face rectangle to be dropped. */
    @Test
    fun payload_omitsScoreModeAndFaceRectangle() {
        val json = discordEmbedPayload(sampleAlert())
        assertFalse("no score field", json.contains("Điểm rủi ro"))
        assertFalse("no percent value", json.contains("%"))
        assertFalse("no scan-mode field", json.contains("Chế độ"))
        assertFalse("no face-rectangle field", json.contains("Vùng khuôn mặt"))
    }

    @Test
    fun blankName_isReportedAsAPlaceholder() {
        val json = discordEmbedPayload(sampleAlert().copy(userName = "  "))
        assertTrue(json.contains("Tên người dùng"))
        assertTrue(json.contains("—"))
    }

    @Test
    fun payload_withImage_pointsTheEmbedAtTheAttachment() {
        val json = discordEmbedPayload(sampleAlert(), hasImage = true)
        assertTrue(json.contains("\"image\":{\"url\":\"attachment://$DISCORD_SCREENSHOT_FILENAME\"}"))
        assertTrue("a screenshot field is advertised", json.contains("Ảnh chụp màn hình"))
    }

    @Test
    fun payload_withoutImage_hasNoImageObject() {
        val json = discordEmbedPayload(sampleAlert(), hasImage = false)
        assertFalse(json.contains("attachment://"))
        assertFalse(json.contains("Ảnh chụp màn hình"))
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

    // ── Multipart body ──

    @Test
    fun multipartBody_framesPayloadAndFile() {
        val json = "{\"embeds\":[]}"
        val image = byteArrayOf(1, 2, 3, 4)
        val body = multipartBody("BOUND", json, image)
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("Content-Disposition: form-data; name=\"payload_json\""))
        assertTrue(text.contains("\"embeds\":[]"))
        assertTrue(
            text.contains(
                "Content-Disposition: form-data; name=\"files[0]\"; filename=\"$DISCORD_SCREENSHOT_FILENAME\""
            )
        )
        assertTrue(text.contains("Content-Type: image/jpeg"))
        assertTrue("closing boundary", text.trimEnd().endsWith("--BOUND--"))
        // The JPEG bytes survive verbatim, sandwiched between the two parts.
        assertTrue("image bytes must be present unchanged", body.indexOfBytes(image) >= 0)
    }

    private fun ByteArray.indexOfBytes(needle: ByteArray): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ── High-risk score ──

    @Test
    fun highRiskScore_isAlwaysInside80To92() {
        repeat(500) {
            val score = randomHighRiskScore()
            assertTrue("score $score under min", score >= HIGH_RISK_SCORE_MIN)
            assertTrue("score $score over max", score <= HIGH_RISK_SCORE_MAX)
        }
    }

    @Test
    fun highRiskScore_canReachBothBounds() {
        val scores = (0 until 2_000).map { randomHighRiskScore() }.toSet()
        assertTrue("min boundary reachable", scores.contains(HIGH_RISK_SCORE_MIN))
        assertTrue("max boundary reachable", scores.contains(HIGH_RISK_SCORE_MAX))
    }
}
