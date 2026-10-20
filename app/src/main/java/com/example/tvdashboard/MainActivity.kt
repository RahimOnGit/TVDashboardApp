package com.example.tvdashboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context

class MainActivity : Activity() {
    private lateinit var webView: WebView

    // ── TMDB ──────────────────────────────────────────────────────
    private val tmdbApiKey = "26ea6ec2da6d71997814fd4e2ceeb5f6"
    private val tmdbBase    = "https://api.themoviedb.org/3"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        webView = WebView(this)
        WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.loadUrl("file:///android_asset/index.html")
        setContentView(webView)
    }

    override fun onResume() {
        super.onResume()
        if (webView.url != null) webView.reload()
    }

    override fun onPause() {
        super.onPause()
        try { webView.freeMemory() } catch (_: Exception) { }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            try { webView.freeMemory() } catch (_: Exception) { }
        }
    }

    override fun onBackPressed() {
        // keep launcher resident
    }

    inner class WebAppInterface {

        @JavascriptInterface
        fun listApps(): String {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            }
            val json = JSONArray()
            pm.queryIntentActivities(mainIntent, 0)
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .forEach { info ->
                    json.put(JSONObject().apply {
                        put("packageName", info.activityInfo.packageName)
                        put("activityName", info.activityInfo.name)
                        put("label", info.loadLabel(pm).toString())
                        try {
                            put("icon", toDataUri(info.loadIcon(pm)))
                        } catch (_: Exception) { }
                    })
                }
            return json.toString()
        }

        @JavascriptInterface
        fun launchApp(packageName: String, activityName: String) {
            try {
                startActivity(Intent().apply {
                    setClassName(packageName, activityName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) { /* app removed or not launchable */ }
        }

        @JavascriptInterface
        fun launchUrl(url: String) {
            if (url.isBlank()) return
            try {
                val clean = if (url.startsWith("http")) url else "https://$url"
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(clean))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w("Launcher", "launchUrl failed", e)
            }
        }

        @JavascriptInterface
        fun getAdbEnabled(): String =
            (Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1).toString()

        @JavascriptInterface
        fun isDefaultLauncher(): String {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val ri = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            return (ri?.activityInfo?.packageName == packageName).toString()
        }

        @JavascriptInterface
        fun getTvInfo(): String {
            val m = resources.displayMetrics
            return "${m.widthPixels}×${m.heightPixels} · Android ${Build.VERSION.RELEASE}"
        }

        // ── Movies (TMDB, native proxy with 24hr Cache) ───────────
        @JavascriptInterface
        fun getTrendingMovies(): String {
            if (!hasTmdbKey()) return """{"results":[]}"""

            val prefs = getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            val cacheTime = prefs.getLong("trending_time", 0)
            val cachedJson = prefs.getString("trending_json", null)

            // 24 hours in milliseconds (86400000)
            val isCacheValid = cachedJson != null && (System.currentTimeMillis() - cacheTime < 86400000L)

            if (isCacheValid) {
                return cachedJson!!
            }

            val url = URL("$tmdbBase/trending/movie/week?api_key=$tmdbApiKey&language=en-US")
            return try {
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                val result = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                
                // Save to cache for next time
                prefs.edit()
                    .putString("trending_json", result)
                    .putLong("trending_time", System.currentTimeMillis())
                    .apply()
                    
                result
            } catch (e: Exception) {
                Log.w("Launcher", "TMDB trending fetch failed", e)
                // If network fails, fallback to old cache if we have one
                cachedJson ?: """{"results":[]}"""
            }
        }

        @JavascriptInterface
        fun openTrailer(movieId: String) {
            if (!hasTmdbKey() || movieId.isBlank()) return
            try {
                val url = URL("$tmdbBase/movie/$movieId/videos?api_key=$tmdbApiKey&language=en-US")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val videos = JSONObject(raw).optJSONArray("results") ?: return
                var key: String? = null
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    if (v.optString("site") == "YouTube" &&
                        v.optString("type") == "Trailer" &&
                        v.optString("key").isNotBlank()
                    ) { key = v.optString("key"); break }
                }
                if (key != null) {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.youtube.com/watch?v=$key")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runOnUiThread { startActivity(intent) }
                }
            } catch (e: Exception) {
                Log.w("Launcher", "Trailer fetch failed", e)
            }
        }

        private fun hasTmdbKey(): Boolean =
            tmdbApiKey.isNotBlank() && tmdbApiKey != "YOUR_TMDB_API_KEY"
    }

    private fun toDataUri(d: Drawable): String {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}