package com.th3web.lean.core

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import libcore.ExchangeContext
import libcore.LocalDNSTransport

/**
 * The system resolver, as the core calls it.
 *
 * Every method here is invoked by the Go core across JNI, on a thread the Go runtime
 * owns, and that is what shapes the error handling: an exception thrown out of one of
 * these lands at a JNI boundary that does not expect one, and ends as a process death
 * with no Java stack, the failure testers describe as «приложение просто пропадает».
 * Nothing may escape. Every path either reports an answer or reports a DNS failure code,
 * exactly once, and the once-only guards are what make a last-resort report safe.
 */
class LocalResolver(private val underlyingNetwork: () -> Network?) : LocalDNSTransport {
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun networkHandle(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            underlyingNetwork()?.networkHandle ?: 0L
        } else {
            0L
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("InlinedApi")
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val reported = AtomicBoolean(false)
        try {
            exchangeOrThrow(ctx, message, reported)
        } catch (fatal: Throwable) {
            // Not a shrug: rawQuery can reject the request outright (no permission, a
            // resolver that is gone), and the alternative to answering "failure" here is
            // taking the whole process down from a background thread.
            if (reported.compareAndSet(false, true)) {
                runCatching { ctx.errorCode(RCODE_FAILURE) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("InlinedApi")
    private fun exchangeOrThrow(ctx: ExchangeContext, message: ByteArray, reported: AtomicBoolean) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val done = CountDownLatch(1)
        val signal = CancellationSignal()
        ctx.onCancel {
            if (reported.compareAndSet(false, true)) {
                signal.cancel()
                done.countDown()
            }
        }
        DnsResolver.getInstance().rawQuery(
            underlyingNetwork(),
            message,
            DnsResolver.FLAG_NO_RETRY,
            DNS_EXECUTOR,
            signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (reported.compareAndSet(false, true)) {
                        ctx.rawSuccess(answer)
                        done.countDown()
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (reported.compareAndSet(false, true)) {
                        val cause = error.cause
                        if (cause is ErrnoException) ctx.errnoCode(cause.errno)
                        else ctx.errorCode(error.code)
                        done.countDown()
                    }
                }
            },
        )
        if (!done.await(DNS_DEADLINE_SECONDS, TimeUnit.SECONDS) &&
            reported.compareAndSet(false, true)
        ) {
            signal.cancel()
            ctx.errorCode(RCODE_TIMEOUT)
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        // one terminal guard for the whole call, created before anything can fail so the
        // last-resort report below cannot answer a request that was already answered.
        val running = AtomicReference<Future<*>?>(null)
        val completion = DnsLookupCompletion { running.get()?.cancel(true) }
        try {
            lookupOrThrow(ctx, network, domain, completion, running)
        } catch (fatal: Throwable) {
            completion.report { runCatching { ctx.errorCode(RCODE_FAILURE) } }
        }
    }

    private fun lookupOrThrow(
        ctx: ExchangeContext,
        network: String,
        domain: String,
        completion: DnsLookupCompletion,
        running: AtomicReference<Future<*>?>,
    ) {
        val task = DNS_EXECUTOR.submit<List<InetAddress>> {
            val result = underlyingNetwork()?.getAllByName(domain) ?: InetAddress.getAllByName(domain)
            when (network) {
                "ip4" -> result.filterIsInstance<Inet4Address>()
                "ip6" -> result.filterIsInstance<Inet6Address>()
                else -> result.toList()
            }
        }
        running.set(task)
        ctx.onCancel(completion::cancel)
        try {
            val answer = task.get(DNS_DEADLINE_SECONDS, TimeUnit.SECONDS)
            completion.report {
                if (answer.isEmpty()) {
                    ctx.errorCode(RCODE_NXDOMAIN)
                } else {
                    ctx.success(answer.mapNotNull { it.hostAddress?.substringBefore('%') }.joinToString("\n"))
                }
            }
        } catch (_: UnknownHostException) {
            completion.report { ctx.errorCode(RCODE_NXDOMAIN) }
        } catch (_: TimeoutException) {
            completion.report {
                task.cancel(true)
                ctx.errorCode(RCODE_TIMEOUT)
            }
        } catch (_: CancellationException) {
            completion.cancel()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            completion.cancel()
        } catch (error: java.util.concurrent.ExecutionException) {
            completion.report {
                if (error.cause is UnknownHostException) ctx.errorCode(RCODE_NXDOMAIN)
                else ctx.errorCode(RCODE_FAILURE)
            }
        }
    }

    private companion object {
        const val DNS_DEADLINE_SECONDS = 10L
        const val RCODE_FAILURE = 2
        const val RCODE_TIMEOUT = 2
        const val RCODE_NXDOMAIN = 3
        val DNS_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "lean-dns").apply { isDaemon = true }
        }
    }
}

internal class DnsLookupCompletion(private val interrupt: () -> Unit) {
    private val terminal = AtomicBoolean(false)

    fun cancel() {
        if (terminal.compareAndSet(false, true)) interrupt()
    }

    fun report(block: () -> Unit): Boolean {
        if (!terminal.compareAndSet(false, true)) return false
        block()
        return true
    }
}
