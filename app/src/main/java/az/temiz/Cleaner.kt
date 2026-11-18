package az.temiz

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object Cleaner {
    private const val JOB_ID = 4091

    private fun avail(am: ActivityManager): Long {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    fun getWhitelist(c: Context): Set<String> {
        val raw = c.getSharedPreferences("s", 0).getStringSet("wl", emptySet()) ?: emptySet()
        return HashSet(raw)
    }

    fun toggleWhitelist(c: Context, pkg: String): Boolean {
        val prefs = c.getSharedPreferences("s", 0)
        val set = HashSet(prefs.getStringSet("wl", emptySet()) ?: emptySet())
        val added = if (set.contains(pkg)) {
            set.remove(pkg)
            false
        } else {
            set.add(pkg)
            true
        }
        prefs.edit().putStringSet("wl", set).apply()
        return added
    }

    fun logHistory(c: Context, appsClosed: Int, mbFreed: Long) {
        val prefs = c.getSharedPreferences("s", 0)
        val arr = try {
            JSONArray(prefs.getString("hist", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val item = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("n", appsClosed)
            .put("mb", mbFreed)
        val next = JSONArray()
        next.put(item)
        val limit = minOf(arr.length(), 29)
        for (idx in 0 until limit) {
            next.put(arr.getJSONObject(idx))
        }
        prefs.edit().putString("hist", next.toString()).apply()
    }

    fun getHistory(c: Context): String {
        return c.getSharedPreferences("s", 0).getString("hist", "[]") ?: "[]"
    }

    fun run(c: Context): JSONObject {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = avail(am)
        val wl = getWhitelist(c)
        var n = 0
        var skipped = 0
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        for (r in c.packageManager.queryIntentActivities(i, 0)) {
            val p = r.activityInfo.packageName
            if (p != c.packageName && seen.add(p)) {
                if (wl.contains(p)) {
                    skipped++
                } else {
                    am.killBackgroundProcesses(p)
                    n++
                }
            }
        }
        c.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        c.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        Thread.sleep(900)
        val mb = maxOf(0L, (avail(am) - before) / 1048576L)
        logHistory(c, n, mb)
        return JSONObject()
            .put("n", n)
            .put("mb", mb)
            .put("s", skipped)
    }

    fun notify(c: Context, r: JSONObject) {
        if (!c.getSharedPreferences("s", 0).getBoolean("n", true)) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("c", "Avtomatik Təmizlik", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openIntent = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            1,
            Notification.Builder(c, "c")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Təmiz Telefon")
                .setContentText("${r.getInt("n")} tətbiq bağlandı · ~${r.getLong("mb")} MB RAM azad edildi")
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()
        )
    }

    fun schedule(c: Context, afterRun: Boolean) {
        val p = c.getSharedPreferences("s", 0)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val pi = PendingIntent.getBroadcast(
            c, 1, Intent(c, CleanReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        js.cancel(JOB_ID)

        if (!p.getBoolean("on", false)) return

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p.getInt("h", 3))
            set(Calendar.MINUTE, p.getInt("m", 0))
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            if (afterRun && p.getInt("d", 1) == 7) add(Calendar.DAY_OF_YEAR, 6)
        }
        val delayMs = maxOf(60_000L, cal.timeInMillis - System.currentTimeMillis())
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)

        try {
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, CleanJobService::class.java))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 3_600_000L)
                .setPersisted(true)
                .build()
            js.schedule(job)
        } catch (e: Exception) {
        }
    }
}

class CleanReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync()
        Thread {
            try {
                Cleaner.notify(c, Cleaner.run(c))
            } catch (e: Exception) {
            } finally {
                Cleaner.schedule(c, true)
                pr.finish()
            }
        }.start()
    }
}

class CleanJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try {
                Cleaner.notify(applicationContext, Cleaner.run(applicationContext))
            } catch (e: Exception) {
            } finally {
                Cleaner.schedule(applicationContext, true)
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Cleaner.schedule(c, false)
    }
}
