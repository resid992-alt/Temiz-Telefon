package az.temiz

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.DownloadManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Size
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var wv: WebView
    private val IMG = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val VID = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        wv = WebView(this)
        setContentView(wv)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.allowFileAccess = true
        wv.webChromeClient = WebChromeClient()
        wv.addJavascriptInterface(Bridge(), "Native")
        wv.loadUrl("file:///android_asset/index.html")

        val need = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val miss = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        wv.evaluateJavascript("window.onShow&&onShow()", null)
    }

    override fun onResume() {
        super.onResume()
        if (::wv.isInitialized) wv.evaluateJavascript("window.onShow&&onShow()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(c: Int, r: Int, d: Intent?) {
        if (c == 77) wv.evaluateJavascript("onDel(${r == RESULT_OK})", null)
        if (c == 78) wv.evaluateJavascript("onDelVid(${r == RESULT_OK})", null)
    }

    private fun go(i: Intent) = runOnUiThread {
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
        }
    }

    private fun iconBase64(d: Drawable): String {
        return try {
            val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, 48, 48)
            d.draw(canvas)
            val bo = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 72, bo)
            Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun ram(): String {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            return JSONObject().put("a", mi.availMem).put("t", mi.totalMem).toString()
        }

        @JavascriptInterface
        fun storage(): String {
            val s = StatFs(Environment.getDataDirectory().path)
            return JSONObject().put("f", s.availableBytes).put("t", s.totalBytes).toString()
        }

        @JavascriptInterface
        fun sysInfo(): String {
            val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scl = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (lvl >= 0 && scl > 0) (lvl * 100 / scl) else 0
            val tempC = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val voltV = (bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
            val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val health = when (bi?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Sağlam"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Həddindən artıq isti"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Zəifləyib"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Yüksək gərginlik"
                else -> "Normal"
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val optIgnored = pm.isIgnoringBatteryOptimizations(packageName)

            return JSONObject()
                .put("bp", pct)
                .put("bt", tempC)
                .put("bv", voltV)
                .put("bh", health)
                .put("bc", charging)
                .put("dev", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("ver", "Android ${Build.VERSION.RELEASE}")
                .put("opt", optIgnored)
                .toString()
        }

        @JavascriptInterface
        fun hasUsage(): Boolean {
            val ao = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            return ao.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            ) == AppOpsManager.MODE_ALLOWED
        }

        @JavascriptInterface
        fun hasMedia(): Boolean {
            val p = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun hasVideo(): Boolean {
            val p = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun openUsage() = go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

        @JavascriptInterface
        fun openStorage() = go(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))

        @JavascriptInterface
        fun openDownloads() = go(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))

        @JavascriptInterface
        fun openApp(p: String) = go(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$p"))
        )

        @JavascriptInterface
        fun uninstall(p: String) = go(Intent(Intent.ACTION_DELETE, Uri.parse("package:$p")))

        @JavascriptInterface
        fun requestIgnoreBatteryOpt() {
            try {
                go(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                go(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        @JavascriptInterface
        fun toggleWl(p: String): Boolean = Cleaner.toggleWhitelist(this@MainActivity, p)

        @JavascriptInterface
        fun apps(): String {
            val pm = packageManager
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val userHandle = Process.myUserHandle()
            val usageOk = hasUsage()
            val wl = Cleaner.getWhitelist(this@MainActivity)
            val now = System.currentTimeMillis()
            val last = HashMap<String, Long>()
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                for (u in usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, now - 365L * 86400000L, now)) {
                    last[u.packageName] = maxOf(last[u.packageName] ?: 0L, u.lastTimeUsed)
                }
            } catch (e: Exception) {
            }

            val arr = JSONArray()
            val seen = HashSet<String>()
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (r in pm.queryIntentActivities(i, 0)) {
                val p = r.activityInfo.packageName
                if (p == packageName || !seen.add(p)) continue
                val f = r.activityInfo.applicationInfo.flags
                if ((f and ApplicationInfo.FLAG_SYSTEM) != 0 && (f and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue
                val inst = try {
                    pm.getPackageInfo(p, 0).firstInstallTime
                } catch (e: Exception) {
                    0L
                }
                var totalBytes = 0L
                var cacheBytes = 0L
                if (usageOk) {
                    try {
                        val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, p, userHandle)
                        totalBytes = st.appBytes + st.dataBytes
                        cacheBytes = st.cacheBytes
                    } catch (e: Exception) {
                    }
                }
                val ic = try {
                    iconBase64(r.loadIcon(pm))
                } catch (e: Exception) {
                    ""
                }
                arr.put(
                    JSONObject()
                        .put("n", r.loadLabel(pm).toString())
                        .put("p", p)
                        .put("l", last[p] ?: 0L)
                        .put("i", inst)
                        .put("sz", totalBytes)
                        .put("ca", cacheBytes)
                        .put("ic", ic)
                        .put("wl", wl.contains(p))
                )
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun kill(): String = Cleaner.run(this@MainActivity).toString()

        @JavascriptInterface
        fun history(): String = Cleaner.getHistory(this@MainActivity)

        @JavascriptInterface
        fun total(): Int =
            contentResolver.query(IMG, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count } ?: 0

        @JavascriptInterface
        fun images(offset: Int, limit: Int): String {
            val q = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            val cols = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            val out = JSONArray()
            contentResolver.query(IMG, cols, q, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    var t = ""
                    try {
                        val bmp = contentResolver.loadThumbnail(ContentUris.withAppendedId(IMG, id), Size(96, 96), null)
                        val bo = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 60, bo)
                        t = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                    } catch (e: Exception) {
                    }
                    out.put(
                        JSONObject()
                            .put("id", id)
                            .put("n", c.getString(1) ?: "")
                            .put("s", c.getLong(2))
                            .put("w", c.getInt(3))
                            .put("h", c.getInt(4))
                            .put("b", c.getString(5) ?: "")
                            .put("r", c.getString(6) ?: "")
                            .put("t", t)
                    )
                }
            }
            return out.toString()
        }

        @JavascriptInterface
        fun fullImage(id: Long): String {
            return try {
                val bmp = contentResolver.loadThumbnail(ContentUris.withAppendedId(IMG, id), Size(720, 720), null)
                val bo = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, bo)
                Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun videos(limit: Int): String {
            val q = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Video.Media.SIZE))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            val cols = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )
            val out = JSONArray()
            contentResolver.query(VID, cols, q, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    var t = ""
                    try {
                        val bmp = contentResolver.loadThumbnail(ContentUris.withAppendedId(VID, id), Size(96, 96), null)
                        val bo = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 60, bo)
                        t = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                    } catch (e: Exception) {
                    }
                    out.put(
                        JSONObject()
                            .put("id", id)
                            .put("n", c.getString(1) ?: "")
                            .put("s", c.getLong(2))
                            .put("d", c.getLong(3))
                            .put("b", c.getString(4) ?: "")
                            .put("t", t)
                    )
                }
            }
            return out.toString()
        }

        @JavascriptInterface
        fun largeFiles(): String {
            val filesUri = MediaStore.Files.getContentUri("external")
            val q = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, 100)
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Files.FileColumns.SIZE} > ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} != ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} != ?"
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(
                        "5242880",
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                    )
                )
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.SIZE))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            val cols = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.MIME_TYPE
            )
            val out = JSONArray()
            try {
                contentResolver.query(filesUri, cols, q, null)?.use { c ->
                    while (c.moveToNext()) {
                        out.put(
                            JSONObject()
                                .put("id", c.getLong(0))
                                .put("n", c.getString(1) ?: "Fayl")
                                .put("s", c.getLong(2))
                                .put("r", c.getString(3) ?: "")
                                .put("m", c.getString(4) ?: "")
                        )
                    }
                }
            } catch (e: Exception) {
            }
            return out.toString()
        }

        @JavascriptInterface
        fun del(ids: String) {
            val a = JSONArray(ids)
            val uris = (0 until a.length()).map { ContentUris.withAppendedId(IMG, a.getLong(it)) }
            runOnUiThread {
                try {
                    startIntentSenderForResult(
                        MediaStore.createDeleteRequest(contentResolver, uris).intentSender,
                        77, null, 0, 0, 0
                    )
                } catch (e: Exception) {
                    wv.evaluateJavascript("onDel(false)", null)
                }
            }
        }

        @JavascriptInterface
        fun delVideos(ids: String) {
            val a = JSONArray(ids)
            val uris = (0 until a.length()).map { ContentUris.withAppendedId(VID, a.getLong(it)) }
            runOnUiThread {
                try {
                    startIntentSenderForResult(
                        MediaStore.createDeleteRequest(contentResolver, uris).intentSender,
                        78, null, 0, 0, 0
                    )
                } catch (e: Exception) {
                    wv.evaluateJavascript("onDelVid(false)", null)
                }
            }
        }

        @JavascriptInterface
        fun schedule(on: Boolean, h: Int, m: Int, d: Int, note: Boolean) {
            getSharedPreferences("s", 0).edit()
                .putBoolean("on", on)
                .putInt("h", h)
                .putInt("m", m)
                .putInt("d", d)
                .putBoolean("n", note)
                .apply()
            Cleaner.schedule(this@MainActivity, false)
        }

        @JavascriptInterface
        fun getSch(): String {
            val p = getSharedPreferences("s", 0)
            return JSONObject()
                .put("on", p.getBoolean("on", false))
                .put("h", p.getInt("h", 3))
                .put("m", p.getInt("m", 0))
                .put("d", p.getInt("d", 1))
                .put("n", p.getBoolean("n", true))
                .toString()
        }
    }
}
