package com.spendcalci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun higherVersionIsNewer() {
        assertTrue(Updater.isNewer("1.2", "1.1"))
        assertTrue(Updater.isNewer("2.0", "1.9.9"))
    }

    @Test fun comparesNumericallyNotAsText() {
        assertTrue(Updater.isNewer("1.10", "1.9"))
        assertFalse(Updater.isNewer("1.9", "1.10"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(Updater.isNewer("1.1", "1.1"))
        assertFalse(Updater.isNewer("1.1", "1.1.0"))
        assertFalse(Updater.isNewer("1.0", "1.1"))
    }

    @Test fun ignoresTagPrefix() {
        assertTrue(Updater.isNewer("v1.2", "1.1"))
        assertFalse(Updater.isNewer("v1.1", "1.1"))
    }
}
