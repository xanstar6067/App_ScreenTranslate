package com.adam.app_screentranslate.game

/** Lifecycle evidence from one query window, without Android dependencies or saved history. */
internal class ForegroundTracker {
    private val active = mutableMapOf<String, MutableSet<String?>>()
    var hasEvidence = false
        private set
    var sawLifecycle = false
        private set

    val packageName: String? get() = active.keys.singleOrNull()
    val ambiguous: Boolean get() = active.size > 1

    fun resumed(pkg: String, activity: String?) {
        hasEvidence = true
        sawLifecycle = true
        active.getOrPut(pkg) { mutableSetOf() }.add(activity)
    }

    fun backgrounded(pkg: String, activity: String?) {
        sawLifecycle = true
        val activities = active[pkg] ?: return
        if (activity == null) activities.clear() else activities.remove(activity)
        if (activities.isEmpty()) active.remove(pkg)
    }

    fun reset() {
        hasEvidence = true
        active.clear()
    }
}
