package com.th3web.lean.core.tun

import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.net.InetAddress
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.th3web.lean.core.CoreManager
import com.th3web.lean.data.PerAppMode
import com.th3web.lean.ui.tr

data class TunRuntimePolicy(
    val ipv6Enabled: Boolean,
    val bypassPrivateNetworks: Boolean,
    val killSwitch: Boolean,
    val perAppMode: PerAppMode,
    val perAppPackages: Set<String>,
    /**
     * «WireGuard MTU», which until now reached only the sing-box side.
     *
     * The dialog is titled for the protocol, not for one implementation of it, and the
     * setting is the one lever a user has against a path that cannot carry full-size
     * packets, the failure where a tunnel handshakes, passes a few kilobytes and then
     * goes quiet, because everything small got through and the first full-size frame did
     * not. AmneziaWG took its MTU from the profile instead, so on that engine the knob did
     * nothing at all.
     *
     * Defaulted so the many call sites that build a policy for a test stay untouched.
     */
    val wgMtu: Int = DEFAULT_WG_MTU,
)

/** Matches SettingsDefaults.WG_MTU; the value that fragments on no network. */
const val DEFAULT_WG_MTU = 1280

class VpnTunController(
    private val service: VpnService,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lean-tun-owner").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val ownership = TunGenerationOwnership()
    private var policy: TunRuntimePolicy? = null
    private var lease: TunLease? = null
    private var underlyingNetwork: Network? = null
    private val onOwnerThread = ThreadLocal<Boolean>()

    fun begin(generation: Long, policy: TunRuntimePolicy) = serialized {
        ownership.begin(generation)
        this.policy = policy
    }

    fun openTun(generation: Long, tunOptionsJson: String, platformOptionsJson: String): Long =
        serialized {
            ownership.requireMayEstablish(generation, lease != null)
            check(VpnService.prepare(service) == null) { "VPN permission not granted" }

            val runtimePolicy = checkNotNull(policy)
            val spec = TunSpecParser.parse(tunOptionsJson, platformOptionsJson)
            establishLease(
                runtimePolicy = runtimePolicy,
                mtu = spec.mtu,
                addresses = buildList {
                    addAll(spec.ipv4Addresses)
                    if (runtimePolicy.ipv6Enabled) addAll(spec.ipv6Addresses)
                },
                dnsServers = listOf(spec.ipv4Dns),
                routes = buildList {
                    addAll(spec.ipv4Routes)
                    if (runtimePolicy.ipv6Enabled) addAll(spec.ipv6Routes)
                },
                routeExcludes = buildList {
                    addAll(spec.ipv4RouteExcludes)
                    if (runtimePolicy.ipv6Enabled) addAll(spec.ipv6RouteExcludes)
                },
                httpProxy = spec.httpProxy,
            )
            checkNotNull(lease).borrowFd().toLong()
        }

    fun openAwgTun(generation: Long, spec: AwgTunSpec): Int = serialized {
        ownership.requireMayEstablish(generation, lease != null)
        check(VpnService.prepare(service) == null) { "VPN permission not granted" }
        val runtimePolicy = checkNotNull(policy)
        establishLease(
            runtimePolicy = runtimePolicy,
            mtu = spec.mtu,
            addresses = spec.addresses,
            dnsServers = spec.dnsServers,
            routes = spec.routes,
            routeExcludes = emptyList(),
            httpProxy = null,
        )
        val detached = checkNotNull(lease).detachFd()
        lease = null
        ownership.markDetached(generation)
        detached
    }

    fun setUnderlyingNetwork(generation: Long, network: Network?) = serialized {
        ownership.requireCurrent(generation)
        underlyingNetwork = network
        if (ownership.hasActiveTun(lease != null)) {
            check(service.setUnderlyingNetworks(network?.let { arrayOf(it) })) {
                "setUnderlyingNetworks failed"
            }
        }
    }

    fun closeDetachedFd(generation: Long, fd: Int) = serialized {
        ownership.requireCurrent(generation)
        check(fd >= 0) { "Detached TUN fd is invalid" }
        ParcelFileDescriptor.adoptFd(fd).close()
    }

    fun closeGeneration(generation: Long) = serialized {
        if (!ownership.closeGeneration(generation)) return@serialized
        lease?.close()
        lease = null
        policy = null
        underlyingNetwork = null
    }

    /**
     * Bounded. This runs from `Service.onDestroy` (on the main thread) and
     * the owner thread it waits for may be parked inside `establish()` or a native call
     * that is not coming back. An unbounded wait there is an ANR and a service that can
     * never finish dying. The descriptor is released by the kernel when the process goes,
     * so giving up on a hung owner is strictly better than hanging with it.
     */
    override fun close() {
        val cleanup = executor.submit {
            onOwnerThread.set(true)
            try {
                lease?.close()
                lease = null
                policy = null
                underlyingNetwork = null
                ownership.closeAll()
            } finally {
                onOwnerThread.remove()
            }
        }
        runCatching { cleanup.get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .onFailure { cleanup.cancel(true) }
        executor.shutdownNow()
    }

    private fun establishLease(
        runtimePolicy: TunRuntimePolicy,
        mtu: Int,
        addresses: List<IpPrefix>,
        dnsServers: List<String>,
        routes: List<IpPrefix>,
        routeExcludes: List<IpPrefix>,
        httpProxy: TunHttpProxy?,
    ) {
        val builder = service.Builder()
            .setSession(SESSION_NAME)
            .setMtu(mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (runtimePolicy.killSwitch) builder.setBlocking(true)
        }

        addresses.forEach { builder.addAddress(it.address, it.prefixLength) }
        dnsServers.forEach(builder::addDnsServer)
        routes.forEach { builder.addRoute(it.address, it.prefixLength) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val excludes = buildList {
                addAll(routeExcludes)
                if (runtimePolicy.bypassPrivateNetworks) addAll(PRIVATE_NETWORKS)
            }
            excludes.forEach { prefix ->
                builder.excludeRoute(
                    android.net.IpPrefix(
                        InetAddress.getByName(prefix.address),
                        prefix.prefixLength,
                    ),
                )
            }
            // The tunnel's own DNS survives the LAN bypass. See [TunRouteRepair]: a
            // WireGuard peer's DNS server is a remote address inside a private range, so
            // excluding 10/8 sent every query out through the physical network, a tunnel
            // that connects, hands the system a resolver, and resolves nothing.
            TunRouteRepair.restore(routes, excludes, dnsServers).forEach { prefix ->
                builder.addRoute(prefix.address, prefix.prefixLength)
                CoreManager.appendLog(
                    tr("маршрут для DNS %s возвращён в туннель мимо обхода локальных сетей")
                        .format(prefix.address),
                )
            }
        }

        when (runtimePolicy.perAppMode) {
            PerAppMode.INCLUDE -> {
                var included = 0
                runtimePolicy.perAppPackages.forEach { packageName ->
                    runCatching { builder.addAllowedApplication(packageName) }
                        .onSuccess { included++ }
                        .onFailure { CoreManager.appendLog(tr("per-app: пропущен пакет %s").format(packageName)) }
                }
                if (included == 0) builder.addAllowedApplication(service.packageName)
            }

            PerAppMode.EXCLUDE -> runtimePolicy.perAppPackages.forEach { packageName ->
                runCatching { builder.addDisallowedApplication(packageName) }
            }

            PerAppMode.OFF -> Unit
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            httpProxy?.let { proxy ->
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(proxy.host, proxy.port, proxy.bypassDomains),
                )
            }
        }

        val established = builder.establish()
            ?: error("VpnService.Builder.establish() returned null")
        TunLease(ParcelTunHandle(established)).also { lease = it }
        check(service.setUnderlyingNetworks(underlyingNetwork?.let { arrayOf(it) })) {
            "setUnderlyingNetworks failed"
        }
    }

    private fun <T> serialized(block: () -> T): T {
        if (onOwnerThread.get() == true) return block()
        val task = executor.submit<T> {
            onOwnerThread.set(true)
            try {
                block()
            } finally {
                onOwnerThread.remove()
            }
        }
        return try {
            task.get()
        } catch (execution: ExecutionException) {
            // Hand back the failure that actually happened. Wrapped, every fault from this
            // class reached the log as "java.util.concurrent.ExecutionException:
            // java.lang.IllegalStateException: …", and a caller that wanted to tell one
            // kind of failure from another had to read the text to do it.
            throw execution.cause ?: execution
        }
    }

    private class ParcelTunHandle(private val descriptor: ParcelFileDescriptor) : TunHandle {
        override val fd: Int
            get() = descriptor.fd

        override fun detachFd(): Int = descriptor.detachFd()

        override fun close() = descriptor.close()
    }

    private companion object {
        const val SESSION_NAME = "Lean"

        /** Long enough for an orderly close, far short of the main-thread ANR budget. */
        const val CLOSE_TIMEOUT_MS = 2_000L
        val PRIVATE_NETWORKS = listOf(
            IpPrefix("10.0.0.0", 8),
            IpPrefix("172.16.0.0", 12),
            IpPrefix("192.168.0.0", 16),
        )
    }
}

internal class TunGenerationOwnership {
    private var generation = 0L
    private var detached = false

    fun begin(generation: Long) {
        check(this.generation == 0L) { "A TUN generation is already active" }
        this.generation = generation
        detached = false
    }

    fun requireCurrent(generation: Long) {
        check(this.generation == generation) { "TUN generation $generation is stale" }
    }

    fun requireMayEstablish(generation: Long, borrowedLeaseExists: Boolean) {
        requireCurrent(generation)
        check(!borrowedLeaseExists && !detached) {
            "TUN generation $generation already established a TUN"
        }
    }

    fun markDetached(generation: Long) {
        requireCurrent(generation)
        check(!detached) { "TUN generation $generation already detached a TUN" }
        detached = true
    }

    fun hasActiveTun(borrowedLeaseExists: Boolean): Boolean =
        borrowedLeaseExists || detached

    fun closeGeneration(generation: Long): Boolean {
        if (this.generation != generation) return false
        this.generation = 0L
        detached = false
        return true
    }

    fun closeAll() {
        generation = 0L
        detached = false
    }
}
