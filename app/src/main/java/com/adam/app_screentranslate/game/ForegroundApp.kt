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
 * answer is estimated from usage lifecycle events. The overlay is a window, not
 * an activity, so tapping the translate button does not move that answer to Lenslate itself.
 *
 * Queries happen only when a translation starts and detection is on. Activity identities are used
 * transiently to match pauses; only the latest result's package and label leave this object.
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
     * A best-effort foreground estimate, with a reason when no game can be selected. Never fall back
     * past a pause, screen-off or shutdown to an old game. Multiple resumed apps are ambiguous.
     */
    @Suppress("DEPRECATION")
    fun current(context: Context): GameDetection {
        val now = System.currentTimeMillis()
        fun result(status: GameDetectionStatus, pkg: String? = null): GameDetection {
            val appLabel = pkg?.let { label(context, it).takeIf(String::isNotBlank) }
            return GameDetection(now, status, pkg, appLabel ?: pkg, appLabel)
        }
        if (!hasAccess(context)) return result(GameDetectionStatus.NO_ACCESS)
        val usage = context.getSystemService(UsageStatsManager::class.java)
            ?: return result(GameDetectionStatus.UNAVAILABLE)
        val resumed = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED
            else UsageEvents.Event.MOVE_TO_FOREGROUND
        val paused = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_PAUSED
            else UsageEvents.Event.MOVE_TO_BACKGROUND
        var sawLifecycle = false
        var available = false
        for (window in WINDOWS) {
            val events = usage.queryEvents(now - window, now) ?: continue
            available = true
            val event = UsageEvents.Event()
            val tracker = ForegroundTracker()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when {
                    event.eventType == resumed -> event.packageName?.let { tracker.resumed(it, event.className) }
                    event.eventType == paused || (Build.VERSION.SDK_INT >= 29 &&
                        event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) ->
                        event.packageName?.let { tracker.backgrounded(it, event.className) }
                    Build.VERSION.SDK_INT >= 28 && (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                        event.eventType == UsageEvents.Event.DEVICE_SHUTDOWN ||
                        event.eventType == UsageEvents.Event.DEVICE_STARTUP) -> tracker.reset()
                }
            }
            sawLifecycle = sawLifecycle || tracker.sawLifecycle
            // A pause alone may belong to another activity: search farther for the missing resumes.
            if (!tracker.hasEvidence) continue
            if (tracker.ambiguous) return result(GameDetectionStatus.MULTIPLE_APPS)
            val pkg = tracker.packageName ?: return result(GameDetectionStatus.BACKGROUND)
            return result(if (ignored(context, pkg)) GameDetectionStatus.IGNORED else GameDetectionStatus.FOUND, pkg)
        }
        return result(when {
            !available -> GameDetectionStatus.UNAVAILABLE
            sawLifecycle -> GameDetectionStatus.BACKGROUND
            else -> GameDetectionStatus.NO_EVENTS
        })
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
