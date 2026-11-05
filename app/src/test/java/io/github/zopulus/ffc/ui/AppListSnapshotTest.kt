package io.github.zopulus.ffc.ui

import org.junit.Assert.*
import org.junit.Test
import java.text.Collator
import java.util.Locale

class AppListSnapshotTest {
    private val apps = listOf("alpha", "beta", "gamma")

    @Test fun groupingPreservesLocaleNameOrder() {
        val collator = Collator.getInstance(Locale.GERMAN)
        val names = listOf("Zebra", "Öl", "Apfel", "Ähre")
            .sortedWith { first, second -> collator.compare(first, second) }
        val visit = AppListSnapshot(setOf("Zebra", "Ähre"))
        assertEquals(listOf("Ähre", "Zebra", "Apfel", "Öl"),
            visit.grouped(names, { it }))
    }

    @Test fun lateConfigLoadUpdatesOrderAndFilterBeforeFirstUserEdit() {
        val session = AppListSession()
        session.snapshots(emptySet(), AppFilter.ALLOWED)
        val loaded = session.snapshots(setOf("gamma"), AppFilter.ALLOWED)
        assertEquals(listOf("gamma", "alpha", "beta"), loaded.order.grouped(apps, { it }))
        assertTrue(loaded.filter.includes("gamma", AppFilter.ALLOWED))
    }

    @Test fun userEditFreezesVisitButChangingFilterRefreshesMembership() {
        val session = AppListSession()
        session.snapshots(setOf("gamma"), AppFilter.ALLOWED)
        session.onUserEdit()
        val toggled = session.snapshots(emptySet(), AppFilter.ALLOWED)
        assertEquals(listOf("gamma", "alpha", "beta"), toggled.order.grouped(apps, { it }))
        assertTrue(toggled.filter.includes("gamma", AppFilter.ALLOWED))
        val changedFilter = session.snapshots(emptySet(), AppFilter.DISABLED)
        assertTrue(changedFilter.filter.includes("gamma", AppFilter.DISABLED))
        val reentered = AppListSession().snapshots(emptySet(), AppFilter.ALL)
        assertEquals(apps, reentered.order.grouped(apps, { it }))
    }

    @Test fun togglesKeepOrderUntilNextVisit() {
        val allowed = mutableSetOf("beta", "gamma")
        val visit = AppListSnapshot(allowed)
        assertEquals(listOf("beta", "gamma", "alpha"), visit.grouped(apps, { it }))
        allowed.remove("beta")
        assertEquals(listOf("beta", "gamma", "alpha"), visit.grouped(apps, { it }))
        assertEquals(listOf("gamma", "alpha", "beta"), AppListSnapshot(allowed).grouped(apps, { it }))
    }

    @Test fun enablingAlsoKeepsOrderUntilNextVisit() {
        val allowed = mutableSetOf("gamma")
        val visit = AppListSnapshot(allowed)
        allowed.add("beta")
        assertEquals(listOf("gamma", "alpha", "beta"), visit.grouped(apps, { it }))
        assertEquals(listOf("beta", "gamma", "alpha"), AppListSnapshot(allowed).grouped(apps, { it }))
    }

    @Test fun filterKeepsRowsUntilExplicitlyRefreshed() {
        val allowed = mutableSetOf("beta")
        val filter = AppListSnapshot(allowed)
        allowed.clear()
        assertTrue(filter.includes("beta", AppFilter.ALLOWED))
        assertFalse(filter.includes("beta", AppFilter.DISABLED))
        assertFalse(AppListSnapshot(allowed).includes("beta", AppFilter.ALLOWED))
        assertTrue(AppListSnapshot(allowed).includes("beta", AppFilter.DISABLED))
    }

    @Test fun filtersPartitionAppsAndAllIncludesBoth() {
        val snapshot = AppListSnapshot(setOf("beta"))
        assertEquals(apps, apps.filter { snapshot.includes(it, AppFilter.ALL) })
        assertEquals(listOf("beta"), apps.filter { snapshot.includes(it, AppFilter.ALLOWED) })
        assertEquals(listOf("alpha", "gamma"), apps.filter { snapshot.includes(it, AppFilter.DISABLED) })
    }

    @Test fun refreshedAppListKeepsGroupingAndDropsRemovedPackages() {
        val visit = AppListSnapshot(setOf("beta", "gamma"))
        assertEquals(listOf("gamma", "alpha", "delta"),
            visit.grouped(listOf("alpha", "delta", "gamma"), { it }))
    }
}
