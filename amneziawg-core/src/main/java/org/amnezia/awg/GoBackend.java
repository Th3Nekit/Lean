package org.amnezia.awg;

import androidx.annotation.Nullable;

public final class GoBackend {
    static {
        System.loadLibrary("wg-go");
    }

    private GoBackend() {
    }

    public static native int awgTurnOn(String interfaceName, int tunFd, String settings);

    public static native void awgTurnOff(int handle);

    public static native int awgGetSocketV4(int handle);

    public static native int awgGetSocketV6(int handle);

    public static native @Nullable String awgGetConfig(int handle);

    public static native String awgVersion();
}
