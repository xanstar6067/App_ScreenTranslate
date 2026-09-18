package com.adam.app_screentranslate.game

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

data class InstalledApp(val packageName: String, val label: String)

/**
 * Which app is on screen. MediaProjection hands over pixels and nothing about their owner, so the
 * answer comes from usage statistics: the last activity to be resumed. The overlay is a window, not
 * an activity, so tapping the translate button does not move that answer to Lenslate itself.
 *
 * Only that one package name is ever taken from the statistics, only when a translation starts, and
 * only while game detection is on. Nothing else is read further and nothing is stored from it.
 */
object ForegroundApp {
    /** Short first: a game resumed minutes ago is found without walking a day of events. */
    private val WINDOWS = listOf(10 * 60_000L, 6 * 3_600_000L, 3 * 86_400_000L)

    // Each spelling of the check has been deprecated at one API level or another; both read the same op.
    @Suppress("DEPRECATION")
    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        // Some systems leave the op at its default and decide by the permission instead.
        return if (mode == AppOpsManager.MODE_DEFAULT)
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        else mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The package of the app being translated, or null when it cannot be told or is not an app a
     * profile makes sense for: Lenslate itself, the home screen, the system interface.
     */
    fun current(context: Context): String? {
        if (!hasAccess(context)) return null
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val resumed = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED
            else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
        val now = System.currentTimeMillis()
        for (window in WINDOWS) {
            val events = usage.queryEvents(now - window, now) ?: continue
            val event = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == resumed) last = event.packageName
            }
            if (last != null) return last.takeUnless { ignored(context, it) }
        }
        return null
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) { "" }

    /** Everything with a launcher icon, for adding a game by hand. */
    fun launchable(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
            .map { InstalledApp(it, label(context, it).ifBlank { it }) }
            .sortedBy { it.label.lowercase() }
    }

    private fun ignored(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName || pkg == "com.android.systemui") return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0).any { it.activityInfo.packageName == pkg }
    }
}
