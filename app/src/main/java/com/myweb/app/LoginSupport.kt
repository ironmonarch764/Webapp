package com.myweb.app

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object LoginSupport {

    // User agent seperti Chrome asli di perangkat INI (model dan versi Android perangkat sendiri), bukan UA WebView
    // ("; wv", "Version/4.0", "Build/...") dan bukan perangkat tiruan. Dibentuk dari UA bawaan WebView di HP ini.
    // Dipakai agar Google/situs melihat sesi masuk dari perangkat yang sebenarnya.
    fun chromeUserAgent(context: Context): String {
        val def = WebSettings.getDefaultUserAgent(context)
        if (!def.contains("Chrome/")) {
            return "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL +
                ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        }
        return def
            .replace("; wv)", ")")
            .replace(Regex("\\s+Build/[^;)]+"), "")
            .replace(" Version/4.0", "")
            .replace(Regex("Chrome/(\\d+)\\.[\\d.]+"), "Chrome/$1.0.0.0")
    }

    fun setup(webView: WebView) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Google menolak login di WebView ber-UA bawaan ("; wv"); pakai UA Chrome milik perangkat ini
            userAgentString = chromeUserAgent(webView.context)
        }
    }

    fun save() {
        CookieManager.getInstance().flush()
    }
}
