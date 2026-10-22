package com.adskipper.core.detect

/** One budget per foreground visit; content changes never renew it. */
class SplashSessionTracker(private val durationMs: Long = 8_000L) {
    data class Session(val id: Long, val packageName: String, val startedAt: Long)
    private var foreground: String? = null
    private var eligible = false
    private var nextId = 0L
    private var active: Session? = null

    fun observeForeground(pkg: String?, allowed: Boolean, now: Long): Session? {
        if (pkg != foreground || allowed != eligible) {
            foreground = pkg
            eligible = allowed
            active = if (pkg != null && allowed) Session(++nextId, pkg, now) else null
        }
        return active?.takeIf { isCurrent(it, now) }
    }

    fun isCurrent(session: Session, now: Long): Boolean =
        active == session && now >= session.startedAt && now - session.startedAt < durationMs

    fun remainingMs(session: Session, now: Long): Long =
        if (isCurrent(session, now)) durationMs - (now - session.startedAt) else 0L

    fun finish(session: Session) {
        if (active == session) active = null
    }

    fun invalidate() { active = null }
}
