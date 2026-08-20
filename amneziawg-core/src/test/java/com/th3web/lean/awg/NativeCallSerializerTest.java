package com.th3web.lean.awg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The invariant a crash was traced to.
 *
 * <p>The Go library behind {@link JniAmneziaWgNative} keeps its tunnels in an unguarded
 * map — turn-on writes it, turn-off deletes from it, get-config reads it. Two of those
 * arriving at once is a fatal error in the Go runtime, which ends the whole process with
 * exit code 2: no Java exception, no signal, no tombstone. Nothing downstream can catch
 * it, so the only place it can be prevented is here.
 */
public class NativeCallSerializerTest {

    @Test
    public void everyCallRunsOnTheSameThreadAndNeverOverlaps() throws Exception {
        NativeCallSerializer serializer = new NativeCallSerializer("test-native");
        Set<String> threads = Collections.synchronizedSet(new HashSet<>());
        AtomicBoolean inside = new AtomicBoolean(false);
        AtomicBoolean overlapped = new AtomicBoolean(false);
        AtomicInteger completed = new AtomicInteger();

        int callers = 8;
        int perCaller = 25;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        for (int c = 0; c < callers; c++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perCaller; i++) {
                        serializer.call(() -> {
                            threads.add(Thread.currentThread().getName());
                            // Two bodies overlapping would mean the map can be touched
                            // concurrently, which is the crash.
                            if (!inside.compareAndSet(false, true)) {
                                overlapped.set(true);
                            }
                            Thread.sleep(1);
                            inside.set(false);
                            completed.incrementAndGet();
                            return null;
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue("callers did not finish", done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertFalse("two native calls ran at once", overlapped.get());
        assertEquals(callers * perCaller, completed.get());
        assertEquals("native work spread across threads: " + threads, 1, threads.size());
        assertEquals("test-native", threads.iterator().next());
    }

    @Test
    public void theResultComesBackToTheCaller() {
        NativeCallSerializer serializer = new NativeCallSerializer("test-result");
        assertEquals(Integer.valueOf(42), serializer.call(() -> 42));
    }

    /** A failure must surface at the call site, not vanish on the worker. */
    @Test
    public void aFailureIsRethrownToTheCaller() {
        NativeCallSerializer serializer = new NativeCallSerializer("test-throw");
        IllegalStateException raised = assertThrows(
                IllegalStateException.class,
                () -> serializer.call(() -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals("boom", raised.getMessage());
    }

    /**
     * A nested call would otherwise wait on a queue only the worker can drain — a silently
     * hung connect rather than a crash, which is harder to diagnose, not easier.
     */
    @Test
    public void aNestedCallRunsInlineInsteadOfDeadlocking() {
        NativeCallSerializer serializer = new NativeCallSerializer("test-nested");
        String name = serializer.call(() ->
                serializer.call(() -> Thread.currentThread().getName()));
        assertEquals("test-nested", name);
    }
}
