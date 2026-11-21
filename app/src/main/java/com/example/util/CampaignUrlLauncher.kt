package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.example.data.model.isValidCampaignTrackingUrl

/**
 * Safe, fail-closed launcher for server-authoritative tracked campaign URLs.
 *
 * Security invariants:
 * - Only launches URLs that pass strict HTTPS + public host validation ([isValidCampaignTrackingUrl]).
 * - Never generates or modifies click IDs, tracking URLs, or partner query parameters on Android.
 * - Prefers Chrome Custom Tabs (via standard Android Custom Tabs Intent extras, requiring no
 *   additional runtime dependencies) with graceful fallback to the device's default HTTPS browser.
 */
object CampaignUrlLauncher {

    private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    private const val EXTRA_CUSTOM_TABS_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"
    private const val EXTRA_CUSTOM_TABS_TITLE_VISIBILITY = "android.support.customtabs.extra.TITLE_VISIBILITY"
    private const val SHOW_PAGE_TITLE = 1

    /**
     * Builds a validated Intent for launching [rawUrl].
     * Returns null (fail closed) if [rawUrl] is blank, non-HTTPS, or fails host validation.
     */
    fun buildSafeCustomTabIntent(
        rawUrl: String,
        toolbarColor: Int? = null
    ): Intent? {
        val cleanUrl = rawUrl.trim()
        if (!isValidCampaignTrackingUrl(cleanUrl)) {
            return null
        }
        val parsedUri = runCatching { Uri.parse(cleanUrl) }.getOrNull() ?: return null
        if (!parsedUri.scheme.equals("https", ignoreCase = true) || parsedUri.host.isNullOrBlank()) {
            return null
        }

        return Intent(Intent.ACTION_VIEW, parsedUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val customTabsExtras = Bundle().apply {
                // Standard Custom Tabs protocol: null IBinder signals a Custom Tabs session intent
                putBinder(EXTRA_CUSTOM_TABS_SESSION, null)
                putInt(EXTRA_CUSTOM_TABS_TITLE_VISIBILITY, SHOW_PAGE_TITLE)
                if (toolbarColor != null) {
                    putInt(EXTRA_CUSTOM_TABS_TOOLBAR_COLOR, toolbarColor)
                }
            }
            putExtras(customTabsExtras)
        }
    }

    /**
     * Launches a server-returned tracked campaign URL in Chrome Custom Tabs or the default external browser.
     * Returns `true` if launch succeeded, `false` if URL validation failed or no browser was available.
     */
    fun openTrackedCampaignUrl(
        context: Context,
        rawUrl: String,
        toolbarColor: Int? = null
    ): Boolean {
        val customTabIntent = buildSafeCustomTabIntent(rawUrl, toolbarColor) ?: return false
        return try {
            context.startActivity(customTabIntent)
            true
        } catch (_: Exception) {
            // Fallback to plain HTTPS external browser Intent without Custom Tabs extras
            try {
                val fallback = Intent(Intent.ACTION_VIEW, customTabIntent.data).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
