package com.myweb.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private var currentUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit().putString("url", v).apply()

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        findViewById<ImageButton>(R.id.menuBtn).setOnClickListener { showMenu() }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(object {
            @JavascriptInterface fun retry() = runOnUiThread { load() }
            @JavascriptInterface fun changeUrl() = runOnUiThread { askUrl(cancelable = true) }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val scheme = req.url.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (e: Exception) { }
                return true
            }
            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) v.loadUrl("file:///android_asset/error.html")
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                progress.progress = p
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { chooser.launch(p.createIntent()); true } catch (e: Exception) { fileCallback = null; false }
            }
        }
        webView.setDownloadListener { url, _, _, _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else if (currentUrl.isBlank()) askUrl(cancelable = false) else load()
    }

    private fun load() { if (currentUrl.isNotBlank()) webView.loadUrl(currentUrl) }

    private fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.contains(" ")) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        val host = Uri.parse(s).host
        return if (host.isNullOrBlank() || !host.contains(".")) null else s
    }

    private fun askUrl(cancelable: Boolean) {
        val input = EditText(this).apply {
            hint = "contoh: komiku.org"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(currentUrl)
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            val m = (20 * resources.displayMetrics.density).toInt()
            setPadding(m, m / 2, m, 0); addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Alamat web")
            .setView(box)
            .setCancelable(cancelable)
            .setPositiveButton("Simpan", null)
        if (cancelable) builder.setNegativeButton("Batal", null)
        val dlg = builder.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = normalize(input.text.toString())
                if (url == null) input.error = "Alamat tidak valid"
                else { currentUrl = url; webView.clearHistory(); load(); dlg.dismiss() }
            }
        }
        dlg.show()
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Ganti alamat web", "Muat ulang", "Ke halaman awal")) { _, i ->
                when (i) {
                    0 -> askUrl(cancelable = true)
                    1 -> webView.reload()
                    2 -> load()
                }
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
