package com.th3web.lean.awg;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs work on ONE thread, always the same one, and waits for the result.
 *
 * <p>Exists for {@link JniAmneziaWgNative}: the Go library behind it keeps its tunnels in
 * an unguarded map that turn-on writes, turn-off deletes and get-config reads. Upstream
 * drives all three from a single backend thread, so the missing lock never shows there.
 * Reading the config from coroutines — which resume on whatever pool thread is free —
 * broke that, and a concurrent map access in Go is a fatal error that ends the process
 * with no exception and no tombstone.
 *
 * <p>Separate from the binding so the invariant can be tested without the native library.
 */
final class NativeCallSerializer {

    private final String threadName;
    private final ExecutorService worker;

    NativeCallSerializer(String threadName) {
        this.threadName = threadName;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    <T> T call(Callable<T> work) {
        // Re-entrancy: a nested call from the worker would wait on a queue only the worker
        // can drain. Nothing does that today, but the failure would be a silently hung
        // connect, so it is answered rather than assumed away.
        if (Thread.currentThread().getName().equals(threadName)) {
            try {
                return work.call();
            } catch (Exception e) {
                throw asUnchecked(e);
            }
        }
        Future<T> future = worker.submit(work);
        try {
            return future.get();
        } catch (InterruptedException e) {
            // The caller's coroutine was cancelled. The native call is already running and
            // cannot be interrupted, so the flag is restored and the failure reported.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("native call interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw asUnchecked(cause != null ? cause : e);
        }
    }

    void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    private static RuntimeException asUnchecked(Throwable t) {
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        return new IllegalStateException(t);
    }
}
