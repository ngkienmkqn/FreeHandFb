package com.example.commenthelper

import java.net.URLEncoder

object FbUrlHelper {
    /** Prefer m.facebook.com so the native FB app opens the post view instead of in-app web. */
    fun normalizeFbUrlForNative(url: String): String {
        return url.trim()
            .replace("www.facebook.com", "m.facebook.com", ignoreCase = true)
            .replace("mbasic.facebook.com", "m.facebook.com", ignoreCase = true)
            .replace("web.facebook.com", "m.facebook.com", ignoreCase = true)
    }

    /**
     * Build the URL/intent target for opening a Facebook post in the app.
     * - share/p links: wrap with fb://faceweb to avoid Composer/camera bugs
     * - everything else: open m.facebook.com directly for native Like/Comment UI
     */
    fun buildFbOpenUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http", ignoreCase = true)) return trimmed

        val mobile = normalizeFbUrlForNative(trimmed)
        return if (mobile.contains("/share/p/", ignoreCase = true)) {
            try {
                "fb://faceweb/f?href=" + URLEncoder.encode(mobile, "UTF-8")
            } catch (_: Exception) {
                mobile
            }
        } else {
            mobile
        }
    }
}
