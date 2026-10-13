package com.adam.app_screentranslate

import com.adam.app_screentranslate.game.ForegroundTracker
import org.junit.Assert.*
import org.junit.Test

class ForegroundTrackerTest {
    @Test fun foregroundGameIsSelected() {
        val tracker = ForegroundTracker()
        tracker.resumed("game.a", "Main")
        assertEquals("game.a", tracker.packageName)
        assertTrue(tracker.hasEvidence)
    }

    @Test fun pausedGameIsNotUsedAsTheCurrentGame() {
        val tracker = ForegroundTracker()
        tracker.resumed("game.a", "Main")
        tracker.backgrounded("game.a", "Main")
        assertNull(tracker.packageName)
        assertTrue(tracker.hasEvidence) // No fallback to older query windows.
    }

    @Test fun switchingGamesWaitsForTheOldActivityToPause() {
        val tracker = ForegroundTracker()
        tracker.resumed("game.a", "Main")
        tracker.resumed("game.b", "Main")
        assertTrue(tracker.ambiguous)
        tracker.backgrounded("game.a", "Main")
        assertEquals("game.b", tracker.packageName)
    }

    @Test fun multipleResumedAppsAreAmbiguous() {
        val tracker = ForegroundTracker()
        tracker.resumed("game.a", "Main")
        tracker.resumed("browser", "Main")
        assertNull(tracker.packageName)
        assertTrue(tracker.ambiguous)
    }

    @Test fun activityTransitionInsideOneGameKeepsTheNewActivity() {
        val tracker = ForegroundTracker()
        tracker.resumed("game.a", "Menu")
        tracker.resumed("game.a", "Battle")
        tracker.backgrounded("game.a", "Menu")
        assertEquals("game.a", tracker.packageName)
        tracker.backgrounded("game.a", "Battle")
        assertNull(tracker.packageName)
    }

    @Test fun lateStopOfAnOldActivityDoesNotClearTheNewOne() {
        val tracker = ForegroundTracker()
        tracker.resumed("game.a", "Menu")
        tracker.backgrounded("game.a", "Menu")
        tracker.resumed("game.a", "Battle")
        tracker.backgrounded("game.a", "Menu")
        assertEquals("game.a", tracker.packageName)
    }

    @Test fun screenOffOrRebootClearsTheGameUntilAnotherResume() {
        val tracker = ForegroundTracker()
        tracker.resumed("game.a", "Main")
        tracker.reset()
        assertNull(tracker.packageName)
        assertTrue(tracker.hasEvidence)
        tracker.resumed("game.b", "Main")
        assertEquals("game.b", tracker.packageName)
    }

    @Test fun homeOrLenslateIsNotSkippedToFindAnOlderGame() {
        val tracker = ForegroundTracker()
        tracker.resumed("game.a", "Main")
        tracker.backgrounded("game.a", "Main")
        tracker.resumed("launcher", "Home")
        assertEquals("launcher", tracker.packageName) // The Android adapter then marks it ignored.
    }

    @Test fun pauseWithoutAResumeNeedsAWiderQueryWindow() {
        val tracker = ForegroundTracker()
        tracker.backgrounded("other.app", "Main")
        assertFalse(tracker.hasEvidence)
        assertTrue(tracker.sawLifecycle)
        assertNull(tracker.packageName)
    }

    @Test fun missingClassNameClearsThePackageConservatively() {
        val tracker = ForegroundTracker()
        tracker.resumed("game.a", null)
        tracker.backgrounded("game.a", null)
        assertNull(tracker.packageName)
    }
}
