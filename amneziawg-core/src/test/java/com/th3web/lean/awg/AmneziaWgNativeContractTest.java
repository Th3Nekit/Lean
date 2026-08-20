package com.th3web.lean.awg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import org.junit.Test;

public final class AmneziaWgNativeContractTest {
    @Test
    public void fakeFacadeExercisesTheWholeTunFdContractWithoutJni() {
        FakeNative fake = new FakeNative();

        assertEquals(17, fake.turnOn("awg0", 41, "private_key=abc\n"));
        fake.turnOff(17);
        assertEquals(101, fake.getSocketV4(17));
        assertEquals(102, fake.getSocketV6(17));
        assertEquals("listen_port=7\n", fake.getConfig(17));
        assertEquals("v0.2.16", fake.version());

        assertEquals("awg0", fake.interfaceName);
        assertEquals(41, fake.tunFd);
        assertEquals("private_key=abc\n", fake.settings);
        assertEquals(17, fake.stoppedHandle);
    }

    @Test
    public void jniOwnerHasExactlySixPublicStaticNativeMethods() throws Exception {
        Class<?> owner = Class.forName(
                "org.amnezia.awg.GoBackend",
                false,
                AmneziaWgNativeContractTest.class.getClassLoader());
        Method[] methods = owner.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));

        assertEquals(6, methods.length);
        assertMethod(methods[0], "awgGetConfig", String.class, int.class);
        assertMethod(methods[1], "awgGetSocketV4", int.class, int.class);
        assertMethod(methods[2], "awgGetSocketV6", int.class, int.class);
        assertMethod(methods[3], "awgTurnOff", void.class, int.class);
        assertMethod(methods[4], "awgTurnOn", int.class, String.class, int.class, String.class);
        assertMethod(methods[5], "awgVersion", String.class);
    }

    @Test
    public void productionFacadeIsInjectableAndOwnsNoStateOrAndroidLifecycleTypes()
            throws Exception {
        assertTrue(AmneziaWgNative.class.isAssignableFrom(JniAmneziaWgNative.class));
        assertFalse(JniAmneziaWgNative.class.isInterface());

        for (Class<?> type : new Class<?>[] {
                AmneziaWgNative.class,
                Class.forName(
                        "org.amnezia.awg.GoBackend",
                        false,
                        AmneziaWgNativeContractTest.class.getClassLoader())
        }) {
            assertEquals(type.getName() + " must not own instance or static state",
                    0,
                    type.getDeclaredFields().length);
            assertNoAndroidOrLifecycleReference(type);
        }

        // The facade owns exactly ONE thing, and it has to: the Go library keeps its
        // tunnels in an unguarded map, so every call has to reach it on one thread or the
        // runtime kills the process outright (see NativeCallSerializerTest). This used to
        // assert zero fields; that was right while the facade was a pure pass-through and
        // became wrong the moment reading the tunnel config made concurrency possible.
        Field[] fields = JniAmneziaWgNative.class.getDeclaredFields();
        assertEquals("the facade must own nothing but its serializer: "
                + Arrays.toString(fields), 1, fields.length);
        assertEquals(NativeCallSerializer.class, fields[0].getType());
        assertTrue("the serializer must be final", Modifier.isFinal(fields[0].getModifiers()));
        assertFalse("it must be per-instance, so the facade stays injectable",
                Modifier.isStatic(fields[0].getModifiers()));
        assertNoAndroidOrLifecycleReference(JniAmneziaWgNative.class);
    }

    private static void assertMethod(
            Method method,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) {
        assertEquals(name, method.getName());
        assertEquals(returnType, method.getReturnType());
        assertArrayEquals(parameterTypes, method.getParameterTypes());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertTrue(Modifier.isNative(method.getModifiers()));
    }

    private static void assertNoAndroidOrLifecycleReference(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            assertTrue("Missing class resource: " + resourceName, input != null);
            String classFile = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String forbidden : new String[] {
                    "android/",
                    "androidx/lifecycle/",
                    "VpnService",
                    "Lifecycle"
            }) {
                assertFalse(
                        type.getName() + " references forbidden Android/lifecycle type: " + forbidden,
                        classFile.contains(forbidden));
            }
        }
    }

    private static final class FakeNative implements AmneziaWgNative {
        private String interfaceName;
        private int tunFd;
        private String settings;
        private int stoppedHandle;

        @Override
        public int turnOn(String interfaceName, int tunFd, String settings) {
            this.interfaceName = interfaceName;
            this.tunFd = tunFd;
            this.settings = settings;
            return 17;
        }

        @Override
        public void turnOff(int handle) {
            stoppedHandle = handle;
        }

        @Override
        public int getSocketV4(int handle) {
            return 101;
        }

        @Override
        public int getSocketV6(int handle) {
            return 102;
        }

        @Override
        public String getConfig(int handle) {
            return "listen_port=7\n";
        }

        @Override
        public String version() {
            return "v0.2.16";
        }
    }
}
