package io.github.zopulus.ffc.ui

/** Package broadcasts are merged until the next scan. A full scan covers all pending changes. */
internal class AppListRefresh {
    sealed interface Request {
        data object All : Request
        data class Changed(val packages: Set<String>) : Request
    }

    private var fullScan = true
    private val changed = mutableSetOf<String>()

    fun invalidate(packageName: String? = null) {
        if (packageName.isNullOrEmpty()) {
            fullScan = true
            changed.clear()
        } else if (!fullScan) {
            changed.add(packageName)
        }
    }

    fun take(): Request? {
        if (fullScan) {
            fullScan = false
            return Request.All
        }
        if (changed.isEmpty()) return null
        return Request.Changed(changed.toSet()).also { changed.clear() }
    }
}
