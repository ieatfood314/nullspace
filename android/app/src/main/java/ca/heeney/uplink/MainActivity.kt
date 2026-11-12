package ca.heeney.uplink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/** The page from ../web, bundled, talking to the phone's Store through UplinkNative. */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var unlisten: (() -> Unit)? = null
    private var geoAsk: Pair<String, GeolocationPermissions.Callback>? = null
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res.values.any { it }
        geoAsk?.let { (origin, cb) -> cb.invoke(origin, ok, false) }
        geoAsk = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/web/", PageFiles()).build()
        web = WebView(this)
        web.setBackgroundColor(0xFF000000.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = false
        }
        web.addJavascriptInterface(Bridge(), "UplinkNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(req.url)
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    geoAsk = origin to callback
                    askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/web/index.html")
        unlisten = Store.changed.listen { main.removeCallbacks(poke); main.postDelayed(poke, 150) }
        handleShare(intent)
        firstRun()
    }

    private val poke = Runnable { web.evaluateJavascript("window.uplinkChanged && uplinkChanged()", null) }

    override fun onResume() {
        super.onResume()
        HubSync.soon(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onDestroy() {
        unlisten?.invoke()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(function(){var d=document.getElementById('sheet');if(d&&d.open){d.close();return 1}return 0})()") { r ->
            if (r != "1") @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Settings until the basics are done: the key, the mic, the hub. */
    private fun firstRun() {
        if (Prefs.sp().getBoolean("setup_seen", false)) return
        Prefs.sp().edit().putBoolean("setup_seen", true).apply()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
        @Suppress("DEPRECATION")
        val img = if (i.type?.startsWith("image/") == true) i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null
        if (text.isBlank() && img == null) return
        Capture.share(this, text, img)
        Notice.show(this, "Saved")
        i.action = null
    }

    inner class Bridge {
        @JavascriptInterface fun sync(body: String): String = Store.pageSync(this@MainActivity, body)

        /** A call that only the hub can answer (address search, redo). */
        @JavascriptInterface fun hub(path: String, method: String, body: String): String {
            val url = HubSync.url() ?: return JSONObject().put("error", "Set the hub address in settings").toString()
            return try {
                val p = path.trimStart('/')
                val out = HubSync.post(this@MainActivity, "$url/$p", if (method == "GET") null else body.toByteArray(), Net.metered(this@MainActivity))
                if (p.startsWith("api/reprocess")) HubSync.run(this@MainActivity)
                String(out)
            } catch (t: Throwable) {
                JSONObject().put("error", t.message ?: "Can't reach the hub").toString()
            }
        }

        @JavascriptInterface fun openSettings() {
            main.post { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
    }

    /** Serves the bundled page, plus screenshots (ours, or the hub's on Wi-Fi) and the Ndot font
     *  (from the hub once, then cached; it isn't open-licensed, so it isn't bundled). */
    inner class PageFiles : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            if (path.startsWith("shots/")) {
                val uid = path.removePrefix("shots/").substringBefore('.').filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                val f = Capture.shotFile(this@MainActivity, uid)
                if (!f.exists()) HubSync.fetchTo(this@MainActivity, "shots/$uid.webp", f)
                return if (f.exists()) WebResourceResponse("image/webp", null, FileInputStream(f)) else null
            }
            if (path == "fonts/ndot55.otf") {
                val f = File(filesDir, "fonts/ndot55.otf")
                if (!f.exists()) HubSync.fetchTo(this@MainActivity, "fonts/ndot55.otf", f)
                return if (f.exists()) WebResourceResponse("font/otf", null, FileInputStream(f)) else null
            }
            return try {
                val type = when {
                    path.endsWith(".html") -> "text/html"
                    path.endsWith(".js") -> "application/javascript"
                    path.endsWith(".css") -> "text/css"
                    path.endsWith(".svg") -> "image/svg+xml"
                    path.endsWith(".ttf") -> "font/ttf"
                    path.endsWith(".otf") -> "font/otf"
                    else -> "application/octet-stream"
                }
                WebResourceResponse(type, "utf-8", assets.open("web/$path"))
            } catch (_: Exception) { null }
        }
    }
}
