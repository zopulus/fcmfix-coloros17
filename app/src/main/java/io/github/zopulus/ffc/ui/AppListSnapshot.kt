package io.github.zopulus.ffc.ui

internal enum class AppFilter(val title: String) {
    ALL("全部"),
    ALLOWED("已开启"),
    DISABLED("已关闭")
}

/** Freeze grouping for a visit, and filter membership until the next selection. */
internal class AppListSnapshot(allowedPackages: Set<String>) {
    private val allowed = allowedPackages.toSet()

    fun includes(packageName: String, filter: AppFilter): Boolean = when (filter) {
        AppFilter.ALL -> true
        AppFilter.ALLOWED -> packageName in allowed
        AppFilter.DISABLED -> packageName !in allowed
    }

    fun <T> sorted(apps: List<T>, packageName: (T) -> String, label: (T) -> String): List<T> =
        apps.sortedWith(compareBy<T> { if (packageName(it) in allowed) 0 else 1 }
            .thenBy { label(it).lowercase() })
}

/** Follow initial/external loads until the user edits this visit; preserve positions afterward. */
internal class AppListSession {
    data class Snapshots(val order: AppListSnapshot, val filter: AppListSnapshot)
    private var userEdited = false
    private var ordering: AppListSnapshot? = null
    private var filtering: AppListSnapshot? = null
    private var lastAllowed: Set<String>? = null
    private var lastFilter: AppFilter? = null

    fun onUserEdit() { userEdited = true }

    fun snapshots(allowed: Set<String>, filter: AppFilter): Snapshots {
        val loaded = ordering == null || !userEdited && lastAllowed != allowed
        if (loaded) {
            lastAllowed = allowed.toSet()
            ordering = AppListSnapshot(allowed)
        }
        if (loaded || filtering == null || lastFilter != filter) filtering = AppListSnapshot(allowed)
        lastFilter = filter
        return Snapshots(requireNotNull(ordering), requireNotNull(filtering))
    }
}
