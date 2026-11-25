package io.github.zopulus.ffc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppListRefreshTest {
    @Test fun resumesWithNoPackageChangesReuseTheLoadedSnapshot() {
        val refresh = AppListRefresh()

        assertEquals(AppListRefresh.Request.All, refresh.take())
        repeat(20) { assertNull(refresh.take()) }
    }

    @Test fun packageChangesDuringAScanQueueOneFollowUp() {
        val refresh = AppListRefresh()
        assertEquals(AppListRefresh.Request.All, refresh.take())

        repeat(3) { refresh.invalidate("one") }
        refresh.invalidate("two")

        assertEquals(AppListRefresh.Request.Changed(setOf("one", "two")), refresh.take())
        assertNull(refresh.take())
    }

    @Test fun manualRefreshCannotBeDowngradedByLaterPackageChanges() {
        val refresh = AppListRefresh()
        refresh.take()
        refresh.invalidate("one")
        refresh.invalidate()
        refresh.invalidate("two")

        assertEquals(AppListRefresh.Request.All, refresh.take())
        assertNull(refresh.take())
    }
}
