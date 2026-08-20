package com.th3web.lean.awg;

import androidx.annotation.Nullable;

public interface AmneziaWgNative {
    int turnOn(String interfaceName, int tunFd, String settings);

    void turnOff(int handle);

    int getSocketV4(int handle);

    int getSocketV6(int handle);

    @Nullable String getConfig(int handle);

    String version();
}
