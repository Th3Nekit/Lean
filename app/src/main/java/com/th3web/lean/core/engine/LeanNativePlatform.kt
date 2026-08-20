package com.th3web.lean.core.engine

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import go.Seq
import java.io.File
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import libcore.BoxInstance
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.LocalDNSTransport
import libcore.NB4AInterface
import com.th3web.lean.core.LocalResolver

internal object WifiStateFormatter {
    fun format(ssid: String?, bssid: String?): String =
        "${ssid.orEmpty().removeSurrounding("\"")},${bssid.orEmpty()}"
}

interface NativeServiceBridge {
    fun openTun(tunJson: String, platformOptionsJson: String): Long
    fun protectSocket(fd: Int)
    fun currentNetwork(): Network?
    fun selectorChanged(tag: String, selected: String)
}

object ActiveNativeService {
    private val lock = Any()
    private var reference = WeakReference<NativeServiceBridge>(null)

    fun install(service: NativeServiceBridge) = synchronized(lock) {
        reference = WeakReference(service)
    }

    fun uninstall(service: NativeServiceBridge) = synchronized(lock) {
        if (reference.get() === service) {
            reference.clear()
            reference = WeakReference(null)
        }
    }

    fun require(): NativeServiceBridge = synchronized(lock) {
        reference.get()
    } ?: error("VPN service is not active")

    fun currentOrNull(): NativeServiceBridge? = synchronized(lock) {
        reference.get()
    }

    fun currentNetwork(): Network? = currentOrNull()?.currentNetwork()

    fun selectorChanged(tag: String, selected: String) {
        currentOrNull()?.selectorChanged(tag, selected)
    }
}

class LeanNativePlatform(context: Context) : BoxPlatformInterface, NB4AInterface {
    private val appContext = context.applicationContext

    // A safe no-op when nothing is installed (currentOrNull, not require): sing-box
    // calls this for every outbound socket it opens regardless of whether our VPN
    // is actually running, so a standalone headless instance dialing off-VPN (see
    // UrlTestPinger, no tunnel to escape, nothing to protect against) must not
    // crash the dial just because ActiveNativeService has nothing installed yet.
    // While a real LeanVpnService is running this behaves exactly as before.
    override fun autoDetectInterfaceControl(fd: Int) {
        ActiveNativeService.currentOrNull()?.protectSocket(fd)
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return -1
        return runCatching {
            manager.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
        }.getOrDefault(-1)
    }

    override fun packageNameByUid(uid: Int): String =
        appContext.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()

    override fun uidByPackageName(packageName: String): Int = runCatching {
        appContext.packageManager.getApplicationInfo(packageName, 0).uid
    }.getOrDefault(-1)

    override fun openTun(options: String, platformOptions: String): Long =
        ActiveNativeService.require().openTun(options, platformOptions)

    @Suppress("DEPRECATION")
    override fun wifiState(): String {
        val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val info = runCatching { manager?.connectionInfo }.getOrNull()
        return WifiStateFormatter.format(info?.ssid?.takeIf(String::isNotBlank), info?.bssid)
    }

    override fun selector_OnProxySelected(tag: String, selected: String) {
        ActiveNativeService.selectorChanged(tag, selected)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun useOfficialAssets(): Boolean = true
}

object NekoNativeRuntime {
    private val initialized = AtomicBoolean(false)
    private val lock = Any()
    private var resolver: LocalDNSTransport? = null

    /**
     * Whether libcore has been brought up in this process. Read by callers that may only
     * touch the core's files while nothing holds them open, see the log trim in
     * [com.th3web.lean.core.LeanVpnService.onCreate].
     */
    val isInitialized: Boolean get() = initialized.get()

    fun ensureInitialized(context: Context): LocalDNSTransport = synchronized(lock) {
        val appContext = context.applicationContext
        val processResolver = resolver ?: LocalResolver(ActiveNativeService::currentNetwork).also {
            resolver = it
        }
        if (!initialized.get()) {
            val platform = LeanNativePlatform(appContext)
            Seq.setContext(appContext)
            val externalAssetsDir = File(appContext.filesDir, "assets").apply { mkdirs() }
            Libcore.initCore(
                currentProcessName(appContext),
                appContext.cacheDir.withTrailingSeparator(),
                appContext.filesDir.withTrailingSeparator(),
                externalAssetsDir.withTrailingSeparator(),
                2048,
                true,
                platform,
                platform,
                processResolver,
            )
            require(Libcore.versionBox().isNotBlank()) { "libcore version is empty" }
            initialized.set(true)
        }
        processResolver
    }

    fun version(): String = synchronized(lock) {
        check(initialized.get()) { "libcore is not initialized" }
        Libcore.versionBox().also {
            require(it.isNotBlank()) { "libcore version is empty" }
        }
    }
}

class LibcoreNekoCore(private val context: Context) : NekoCore {
    override fun newInstance(config: String): NekoBox {
        val resolver = NekoNativeRuntime.ensureInitialized(context)
        NekoNativeRuntime.version()
        return LibcoreNekoBox(Libcore.newSingBoxInstance(config, resolver))
    }
}

class LibcoreNekoBox(private val instance: BoxInstance) : NekoBox {
    override fun setAsMain() = instance.setAsMain()
    override fun setV2rayStats(tags: String) = instance.setV2rayStats(tags)
    override fun start() = instance.start()
    override fun close() = instance.close()
    override fun queryStats(tag: String, direction: String): Long = instance.queryStats(tag, direction)
    override fun selectOutbound(tag: String): Boolean = instance.selectOutbound(tag)
    override fun urlTest(url: String, timeoutMs: Int): Int = Libcore.urlTest(instance, url, timeoutMs)
    override fun clearLogs() = Libcore.nekoLogClear()
    override fun sleep() = instance.sleep()
    override fun wake() = instance.wake()

    override fun resetNetwork() = instance.resetNetwork()
}

private fun File.withTrailingSeparator(): String =
    absolutePath.trimEnd(File.separatorChar) + File.separator

private fun currentProcessName(context: Context): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return android.app.Application.getProcessName()
    val pid = android.os.Process.myPid()
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return manager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        ?: context.packageName
}
