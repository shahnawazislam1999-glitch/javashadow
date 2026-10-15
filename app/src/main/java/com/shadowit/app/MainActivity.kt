package com.shadowit.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Native Kotlin host for the SHADOWit web app (bundled in assets/index.html).
 *
 * The page is served from https://appassets.androidplatform.net/ so that
 * localStorage / IndexedDB behave like a real origin and persist between launches.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var assetLoader: WebViewAssetLoader

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingSave: ByteArray? = null

    // ---- Activity result launchers (must be registered before STARTED) ----

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uris: Array<Uri>? = if (result.resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                when {
                    clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else null
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingPermissionRequest
            pendingPermissionRequest = null
            if (req != null) {
                if (granted) req.grant(req.resources) else req.deny()
            }
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val bytes = pendingSave
            pendingSave = null
            if (uri != null && bytes != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not save file", Toast.LENGTH_LONG).show()
                }
            }
        }

    // ---- JS bridge ----

    inner class Bridge {
        @JavascriptInterface
        fun saveFile(fileName: String, mimeType: String, base64: String) {
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return
            }
            runOnUiThread {
                pendingSave = bytes
                saveLauncher.launch(fileName)
            }
        }

        @JavascriptInterface
        fun keepScreenOn(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        root = FrameLayout(this)
        webView = WebView(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppChromeClient()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // Intentionally NOT calling webView.onPause()/pauseTimers(): the app is an audio
    // player and should keep playing when the screen is off or the app is in background.

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        root.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    // ---- Full-screen video support ----

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webView.visibility = View.GONE
        setSystemBarsHidden(true)
    }

    private fun hideCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        webView.visibility = View.VISIBLE
        setSystemBarsHidden(false)
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !hidden)
        if (hidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- WebViewClient ----

    private inner class AppWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.host == HOST) return false // stay inside the app
            // wa.me, ChatGPT, mailto:, tel:, etc. -> hand over to the system
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (url != null && Uri.parse(url).host == HOST) {
                view.evaluateJavascript(INJECTED_JS, null)
            }
        }
    }

    // ---- WebChromeClient ----

    private inner class AppChromeClient : WebChromeClient() {

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // "*/*" on purpose: .mkv / .srt / .json have unreliable MIME types on Android.
                type = "*/*"
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                )
            }
            return try {
                filePicker.launch(Intent.createChooser(intent, null))
                true
            } catch (e: Exception) {
                fileCallback?.onReceiveValue(null)
                fileCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                if (request.origin.host != HOST) {
                    request.deny()
                    return@runOnUiThread
                }
                val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsMic) {
                    request.deny()
                    return@runOnUiThread
                }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingPermissionRequest?.deny()
                    pendingPermissionRequest = request
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) =
            showCustomView(view, callback)

        override fun onHideCustomView() = hideCustomView()

        // --- JavaScript dialogs (WebView shows nothing unless we implement them) ---

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult
        ): Boolean {
            val input = EditText(this@MainActivity).apply { setText(defaultValue ?: "") }
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/assets/index.html"

        /**
         * 1) Blob/anchor downloads (backup export) -> native "Save as" dialog.
         * 2) navigator.wakeLock shim -> keeps the screen on via the native window flag.
         */
        private const val INJECTED_JS = """
(function () {
  if (window.__shadowitBridge) return;
  window.__shadowitBridge = true;

  function sendBlob(name, href) {
    fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
      var fr = new FileReader();
      fr.onloadend = function () {
        var d = String(fr.result);
        AndroidBridge.saveFile(name || 'download', b.type || 'application/octet-stream', d.substring(d.indexOf(',') + 1));
      };
      fr.readAsDataURL(b);
    });
  }
  function handle(a) {
    if (a && a.hasAttribute && a.hasAttribute('download') && String(a.href).indexOf('blob:') === 0) {
      sendBlob(a.getAttribute('download'), a.href);
      return true;
    }
    return false;
  }

  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (!handle(this)) return origClick.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (a && handle(a)) e.preventDefault();
  }, true);

  try {
    Object.defineProperty(navigator, 'wakeLock', {
      configurable: true,
      value: {
        request: function () {
          AndroidBridge.keepScreenOn(true);
          var sentinel = {
            released: false,
            release: function () { this.released = true; AndroidBridge.keepScreenOn(false); return Promise.resolve(); },
            addEventListener: function () {},
            removeEventListener: function () {}
          };
          return Promise.resolve(sentinel);
        }
      }
    });
  } catch (e) {}
})();
"""
    }
}
