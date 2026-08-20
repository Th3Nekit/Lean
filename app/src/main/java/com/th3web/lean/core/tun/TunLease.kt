package com.th3web.lean.core.tun

interface TunHandle {
    val fd: Int

    fun detachFd(): Int

    fun close()
}

class TunLease(private val handle: TunHandle) : AutoCloseable {
    private var state = State.OWNED

    @Synchronized
    fun borrowFd(): Int {
        check(state == State.OWNED) { "TUN descriptor is no longer owned" }
        return handle.fd
    }

    @Synchronized
    fun detachFd(): Int {
        check(state == State.OWNED) { "TUN descriptor can only be detached once" }
        return handle.detachFd().also { state = State.DETACHED }
    }

    @Synchronized
    override fun close() {
        if (state != State.OWNED) return
        state = State.CLOSED
        handle.close()
    }

    private enum class State {
        OWNED,
        DETACHED,
        CLOSED,
    }
}
