package io.github.zopulus.ffc.ui

/** Main-thread queue. Changes received during a scan request one follow-up. */
internal class AppListRefresh {
    enum class Mode { AUTOMATIC, FORCED }

    private var pending: Mode? = Mode.AUTOMATIC

    fun invalidate(force: Boolean = false) {
        if (force) pending = Mode.FORCED
        else if (pending == null) pending = Mode.AUTOMATIC
    }

    fun take(): Mode? = pending.also { pending = null }
}
