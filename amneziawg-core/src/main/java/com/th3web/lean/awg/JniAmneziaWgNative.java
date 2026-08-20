package com.th3web.lean.awg;

import androidx.annotation.Nullable;

import org.amnezia.awg.GoBackend;

/**
 * The AmneziaWG native calls, every one of them on ONE dedicated thread.
 *
 * <p>This is not an optimisation. The Go library keeps its tunnels in a plain, unguarded
 * {@code map[int32]TunnelHandle}: {@code awgTurnOn} writes to it, {@code awgTurnOff}
 * deletes from it, and {@code awgGetConfig} reads it. Upstream's own Android app drives
 * all of that from a single backend thread, so the map is never touched concurrently and
 * the missing lock never shows.
 *
 * <p>Lean broke that assumption the moment it started reading the config — once to wait
 * for a real handshake before reporting «подключено», and again to poll the traffic
 * counters. Both run inside coroutines, and a coroutine resumes on whatever thread of the
 * IO pool is free, so those reads landed on arbitrary threads while a connect or a
 * disconnect wrote from another. A concurrent map access is not an exception in Go; the
 * runtime calls it a fatal error and ends the PROCESS with exit code 2 — no Java
 * exception, no signal, no tombstone, nothing for a crash reporter to catch. On a device
 * it looks exactly like what was reported: the app vanishes the moment an AmneziaWG
 * tunnel comes up.
 *
 * <p>Serialising here rather than at each call site is deliberate: the constraint belongs
 * to the library, so the boundary to the library is where it is enforced, and no future
 * caller has to know about it.
 */
public final class JniAmneziaWgNative implements AmneziaWgNative {

    /**
     * One thread for the life of the process. Beyond serialising, this keeps libwg-go's Go
     * runtime on a single thread of its own — worth having in a process that also hosts a
     * SECOND Go runtime (the sing-box core's libgojni.so).
     */
    private final NativeCallSerializer calls = new NativeCallSerializer("lean-awg-native");

    @Override
    public int turnOn(String interfaceName, int tunFd, String settings) {
        return calls.call(() -> GoBackend.awgTurnOn(interfaceName, tunFd, settings));
    }

    @Override
    public void turnOff(int handle) {
        calls.run(() -> GoBackend.awgTurnOff(handle));
    }

    @Override
    public int getSocketV4(int handle) {
        return calls.call(() -> GoBackend.awgGetSocketV4(handle));
    }

    @Override
    public int getSocketV6(int handle) {
        return calls.call(() -> GoBackend.awgGetSocketV6(handle));
    }

    @Override
    public @Nullable String getConfig(int handle) {
        return calls.call(() -> GoBackend.awgGetConfig(handle));
    }

    @Override
    public String version() {
        return calls.call(GoBackend::awgVersion);
    }
}
