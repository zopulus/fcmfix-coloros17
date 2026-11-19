package io.github.zopulus.ffc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppListRefreshTest {
    @Test fun resumesWithNoPackageChangesReuseTheLoadedSnapshot() {
        val refresh = AppListRefresh()

        assertEquals(AppListRefresh.Mode.AUTOMATIC, refresh.take())
        repeat(20) { assertNull(refresh.take()) }
    }

    @Test fun packageChangesDuringAScanQueueOneFollowUp() {
        val refresh = AppListRefresh()
        assertEquals(AppListRefresh.Mode.AUTOMATIC, refresh.take())

        repeat(3) { refresh.invalidate() }

        assertEquals(AppListRefresh.Mode.AUTOMATIC, refresh.take())
        assertNull(refresh.take())
    }

    @Test fun manualRefreshCannotBeDowngradedByLaterPackageChanges() {
        val refresh = AppListRefresh()
        refresh.invalidate()
        refresh.invalidate(force = true)
        refresh.invalidate()

        assertEquals(AppListRefresh.Mode.FORCED, refresh.take())
        assertNull(refresh.take())
    }
}
