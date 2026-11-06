package org.librefit.gzclp.domain

/** Null accumulated time means a legacy session whose active duration is unknown. */
data class SessionClock(val accumulatedMillis: Long?, val runningSinceMillis: Long?) {
    fun elapsed(nowMillis: Long): Long? = accumulatedMillis?.let { accumulated ->
        accumulated + (runningSinceMillis?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0L)
    }

    fun pause(nowMillis: Long): SessionClock = SessionClock(elapsed(nowMillis), null)

    fun resume(nowMillis: Long): SessionClock {
        require(accumulatedMillis != null) { "Set the active duration before resuming this older session." }
        return if (runningSinceMillis == null) copy(runningSinceMillis = nowMillis) else this
    }

    fun resolve(activeMinutes: Long): SessionClock {
        require(accumulatedMillis == null && activeMinutes >= 0 && activeMinutes <= Long.MAX_VALUE / 60_000)
        return SessionClock(activeMinutes * 60_000, null)
    }
}
