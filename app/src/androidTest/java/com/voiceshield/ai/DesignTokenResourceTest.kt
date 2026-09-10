package com.voiceshield.ai

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import androidx.core.content.ContextCompat

/**
 * Instrumented tests verifying the design token resources resolve correctly.
 * These tests ensure the new pastel palette is wired up and accessible.
 */
@RunWith(AndroidJUnit4::class)
class DesignTokenResourceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mintColors_resolve() {
        val mint50 = ContextCompat.getColor(context, R.color.mint_50)
        val mint500 = ContextCompat.getColor(context, R.color.mint_500)
        val mint600 = ContextCompat.getColor(context, R.color.mint_600)
        assertNotEquals(0, mint50)
        assertNotEquals(0, mint500)
        assertNotEquals(0, mint600)
    }

    @Test
    fun terracottaColors_resolve() {
        val terracotta = ContextCompat.getColor(context, R.color.terracotta)
        val terracottaSoft = ContextCompat.getColor(context, R.color.terracotta_soft)
        val terracottaDeep = ContextCompat.getColor(context, R.color.terracotta_deep)
        assertNotEquals(0, terracotta)
        assertNotEquals(0, terracottaSoft)
        assertNotEquals(0, terracottaDeep)
    }

    @Test
    fun surfaceAndTextColors_resolve() {
        assertNotEquals(0, ContextCompat.getColor(context, R.color.bg_page))
        assertNotEquals(0, ContextCompat.getColor(context, R.color.surface))
        assertNotEquals(0, ContextCompat.getColor(context, R.color.outline))
        assertNotEquals(0, ContextCompat.getColor(context, R.color.text_primary))
        assertNotEquals(0, ContextCompat.getColor(context, R.color.text_secondary))
    }

    @Test
    fun riskColors_resolve() {
        assertNotEquals(0, ContextCompat.getColor(context, R.color.risk_safe))
        assertNotEquals(0, ContextCompat.getColor(context, R.color.risk_caution))
        assertNotEquals(0, ContextCompat.getColor(context, R.color.risk_danger))
    }

    @Test
    fun dimens_resolve() {
        val cardRadius = context.resources.getDimension(R.dimen.radius_card)
        val pillRadius = context.resources.getDimension(R.dimen.radius_pill)
        val hairline = context.resources.getDimension(R.dimen.stroke_hairline)
        assertTrue("Card radius should be > 0", cardRadius > 0f)
        assertTrue("Pill radius should be > card radius", pillRadius > cardRadius)
        assertTrue("Hairline should be > 0", hairline > 0f)
    }

    @Test
    fun drawables_resolve() {
        // Verify all new flat drawables can be loaded
        val drawables = listOf(
            R.drawable.bg_card,
            R.drawable.bg_card_tint,
            R.drawable.bg_pill_primary,
            R.drawable.bg_pill_soft,
            R.drawable.bg_pill_outline,
            R.drawable.bg_pill_danger,
            R.drawable.bg_circle_soft,
            R.drawable.bg_tile_soft,
            R.drawable.bg_badge_terracotta,
            R.drawable.bg_badge_mint,
            R.drawable.bg_bottom_nav,
            R.drawable.bg_scrim,
            R.drawable.bg_nav_indicator,
            R.drawable.bg_app_logo,
            R.drawable.bg_status_card,
            R.drawable.bg_status_icon
        )
        drawables.forEach { id ->
            val drawable = ContextCompat.getDrawable(context, id)
            assertNotNull("Drawable $id should load", drawable)
        }
    }

    @Test
    fun lineIcons_resolve() {
        val icons = listOf(
            R.drawable.ic_home,
            R.drawable.ic_shield,
            R.drawable.ic_radar,
            R.drawable.ic_history,
            R.drawable.ic_alert,
            R.drawable.ic_check,
            R.drawable.ic_close,
            R.drawable.ic_waveform
        )
        icons.forEach { id ->
            val drawable = ContextCompat.getDrawable(context, id)
            assertNotNull("Icon $id should load", drawable)
        }
    }

    @Test
    fun colorSelectors_resolve() {
        val selectors = listOf(
            R.color.color_pill_primary,
            R.color.color_pill_soft,
            R.color.color_pill_outline,
            R.color.color_pill_danger,
            R.color.color_nav_icon,
            R.color.color_nav_label
        )
        selectors.forEach { id ->
            val color = ContextCompat.getColor(context, id)
            assertNotEquals("Selector $id should resolve", 0, color)
        }
    }
}