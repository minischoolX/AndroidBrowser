/*
 * Copyright (c) 2020 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.mobile.android.vpn.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.*
import android.system.OsConstants.AF_INET6
import androidx.core.content.ContextCompat
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.global.extensions.getPrivateDnsServerName
import com.duckduckgo.app.global.plugins.PluginPoint
import com.duckduckgo.app.utils.ConflatedJob
import com.duckduckgo.app.utils.checkMainThread
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.appbuildconfig.api.isInternalBuild
import com.duckduckgo.di.scopes.VpnScope
import com.duckduckgo.library.loader.LibraryLoader
import com.duckduckgo.mobile.android.vpn.dao.VpnServiceStateStatsDao
import com.duckduckgo.mobile.android.vpn.feature.AppTpFeatureConfig
import com.duckduckgo.mobile.android.vpn.feature.AppTpSetting
import com.duckduckgo.mobile.android.vpn.integration.VpnNetworkStackProvider
import com.duckduckgo.mobile.android.vpn.model.AlwaysOnState
import com.duckduckgo.mobile.android.vpn.model.VpnServiceState
import com.duckduckgo.mobile.android.vpn.model.VpnServiceState.ENABLED
import com.duckduckgo.mobile.android.vpn.model.VpnServiceState.ENABLING
import com.duckduckgo.mobile.android.vpn.model.VpnServiceStateStats
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason
import com.duckduckgo.mobile.android.vpn.network.VpnNetworkStack
import com.duckduckgo.mobile.android.vpn.network.VpnNetworkStack.VpnTunnelConfig
import com.duckduckgo.mobile.android.vpn.network.util.asRoute
import com.duckduckgo.mobile.android.vpn.network.util.getActiveNetwork
import com.duckduckgo.mobile.android.vpn.network.util.getSystemActiveNetworkDefaultDns
import com.duckduckgo.mobile.android.vpn.network.util.isLocal
import com.duckduckgo.mobile.android.vpn.pixels.DeviceShieldPixels
import com.duckduckgo.mobile.android.vpn.prefs.VpnPreferences
import com.duckduckgo.mobile.android.vpn.service.state.VpnStateMonitorService
import com.duckduckgo.mobile.android.vpn.state.VpnStateMonitor.VpnStopReason
import com.duckduckgo.mobile.android.vpn.ui.notification.VpnEnabledNotificationBuilder
import com.squareup.anvil.annotations.ContributesTo
import dagger.Binds
import dagger.Module
import dagger.android.AndroidInjection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.properties.Delegates
import kotlin.system.exitProcess
import kotlinx.coroutines.*
import logcat.LogPriority
import logcat.LogPriority.ERROR
import logcat.LogPriority.WARN
import logcat.asLog
import logcat.logcat

@InjectWith(VpnScope::class)
class TrackerBlockingVpnService : VpnService(), CoroutineScope by MainScope(), VpnSocketProtector {

    private external fun jni_wait_for_tun_up(tunFd: Int): Int

    fun ParcelFileDescriptor.waitForTunellUpOrTimeout(): Boolean {
        return runCatching {
            jni_wait_for_tun_up(this.fd) == 0
        }.getOrElse { e ->
            if (e is UnsatisfiedLinkError) {
                logcat(ERROR) { "VPN log: ${e.asLog()}" }
                // A previous error unloaded the libraries, reload them
                try {
                    logcat { "VPN log: Loading native VPN networking library" }
                    LibraryLoader.loadLibrary(this@TrackerBlockingVpnService, "netguard")
                } catch (ignored: Throwable) {
                    logcat(ERROR) { "VPN log: Error loading netguard library: ${ignored.asLog()}" }
                    exitProcess(1)
                }
            }
            Thread.sleep(100)
            true
        }
    }

    @Inject
    lateinit var vpnPreferences: VpnPreferences

    @Inject
    lateinit var deviceShieldPixels: DeviceShieldPixels

    @Inject
    lateinit var vpnServiceCallbacksPluginPoint: PluginPoint<VpnServiceCallbacks>

    @Inject
    lateinit var memoryCollectorPluginPoint: PluginPoint<VpnMemoryCollectorPlugin>

    @Inject
    lateinit var vpnEnabledNotificationContentPluginPoint: PluginPoint<VpnEnabledNotificationContentPlugin>

    private var activeTun by Delegates.observable<ParcelFileDescriptor?>(null) { _, oldTun, newTun ->
        fun ParcelFileDescriptor?.safeFd(): Int? {
            return runCatching { this?.fd }.getOrNull()
        }
        runCatching {
            logcat { "VPN log: New tun ${newTun?.safeFd()}" }
            logcat { "VPN log: Closing old tun ${oldTun?.safeFd()}" }
            oldTun?.close()
        }.onFailure {
            logcat(ERROR) { "VPN log: Error closing old tun ${oldTun?.safeFd()}" }
        }
    }

    private val binder: VpnServiceBinder = VpnServiceBinder()

    private var vpnStateServiceReference: IBinder? = null

    @Inject lateinit var appBuildConfig: AppBuildConfig

    @Inject lateinit var appTpFeatureConfig: AppTpFeatureConfig

    @Inject lateinit var vpnNetworkStackProvider: VpnNetworkStackProvider

    @Inject lateinit var vpnServiceStateStatsDao: VpnServiceStateStatsDao

    private val alwaysOnStateJob = ConflatedJob()

    private val serviceDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val isInterceptDnsTrafficEnabled by lazy {
        appTpFeatureConfig.isEnabled(AppTpSetting.InterceptDnsTraffic)
    }

    private val isPrivateDnsSupportEnabled by lazy {
        appTpFeatureConfig.isEnabled(AppTpSetting.PrivateDnsSupport)
    }

    private val isAlwaysSetDNSEnabled by lazy {
        appTpFeatureConfig.isEnabled(AppTpSetting.AlwaysSetDNS)
    }

    private var vpnNetworkStack: VpnNetworkStack by VpnNetworkStackDelegate(provider = { vpnNetworkStackProvider.provideNetworkStack() })

    private val vpnStateServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?,
        ) {
            logcat { "Connected to state monitor service" }
            vpnStateServiceReference = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logcat { "Disconnected from state monitor service" }
            vpnStateServiceReference = null
        }
    }

    inner class VpnServiceBinder : Binder() {

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code == LAST_CALL_TRANSACTION) {
                onRevoke()
                return true
            }
            return false
        }

        fun getService(): TrackerBlockingVpnService {
            return this@TrackerBlockingVpnService
        }
    }

    override fun onCreate() {
        super.onCreate()
        AndroidInjection.inject(this)

        logcat { "VPN log: onCreate, creating the ${vpnNetworkStack.name} network stack" }
        vpnNetworkStack.onCreateVpnWithErrorReporting()
    }

    override fun onBind(intent: Intent?): IBinder {
        logcat { "VPN log: onBind invoked" }
        return binder
    }

    override fun onUnbind(p0: Intent?): Boolean {
        logcat { "VPN log: onUnbind invoked" }
        return super.onUnbind(p0)
    }

    override fun onDestroy() {
        logcat { "VPN log: onDestroy" }
        vpnNetworkStack.onDestroyVpn()
        super.onDestroy()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        logcat { "VPN log: onStartCommand: ${intent?.action}" }

        var returnCode: Int = Service.START_NOT_STICKY

        when (val action = intent?.action) {
            ACTION_START_VPN, ACTION_ALWAYS_ON_START -> {
                notifyVpnStart()
                synchronized(this) {
                    launch(serviceDispatcher) { startVpn() }
                }
                returnCode = Service.START_REDELIVER_INTENT
            }
            ACTION_STOP_VPN -> {
                synchronized(this) {
                    launch(serviceDispatcher) { stopVpn(VpnStopReason.SELF_STOP) }
                }
            }
            ACTION_RESTART_VPN -> {
                synchronized(this) {
                    launch(serviceDispatcher) { startVpn() }
                }
            }
            else -> logcat(ERROR) { "Unknown intent action: $action" }
        }

        return returnCode
    }

    private suspend fun startVpn() = withContext(serviceDispatcher) {
        fun updateNetworkStackUponRestart() {
            logcat { "VPN log: updating the networking stack" }
            logcat { "VPN log: CURRENT network ${vpnNetworkStack.name}" }
            // stop the current networking stack
            vpnNetworkStack.onStopVpn(VpnStopReason.RESTART)
            vpnNetworkStack.onDestroyVpn()
            // maybe we have changed the networking stack
            vpnNetworkStack = vpnNetworkStackProvider.provideNetworkStack()
            vpnNetworkStack.onCreateVpnWithErrorReporting()
            logcat { "VPN log: NEW network ${vpnNetworkStack.name}" }
        }

        vpnServiceStateStatsDao.insert(createVpnState(state = ENABLING))

        logcat { "VPN log: Starting VPN" }
        val restarting = activeTun != null

        if (!restarting) {
            vpnServiceCallbacksPluginPoint.getPlugins().forEach {
                logcat { "VPN log: onVpnStarting ${it.javaClass} callback" }
                it.onVpnStarting(this)
            }
        } else {
            logcat { "VPN log: skipping service callbacks while restarting" }
        }

        // Create a null route tunnel so that leaks can't scape
        val nullTun = createNullRouteTempTunnel()?.let {
            if (!it.waitForTunellUpOrTimeout()) {
                logcat(WARN) { "VPN log: timeout waiting for null tunnel to go up" }
            }
            it
        }
        activeTun?.let {
            logcat { "VPN log: restarting the tunnel" }
            updateNetworkStackUponRestart()
            it
        }
        activeTun = nullTun

        vpnNetworkStack.onPrepareVpn().getOrNull().also {
            if (it != null) {
                activeTun = createTunnelInterface(it)
                activeTun?.let { tun ->
                    if (!tun.waitForTunellUpOrTimeout()) {
                        activeTun = null
                    }
                }
            } else {
                logcat(ERROR) { "VPN log: Failed to obtain config needed to establish the TUN interface" }
                stopVpn(VpnStopReason.ERROR, false)
                return@withContext
            }
        }

        if (activeTun == null) {
            logcat(ERROR) { "VPN log: Failed to establish the TUN interface" }
            deviceShieldPixels.vpnEstablishTunInterfaceError()
            stopVpn(VpnStopReason.ERROR, false)
            return@withContext
        }

        if (isInterceptDnsTrafficEnabled) {
            applicationContext.getActiveNetwork()?.let { an ->
                logcat { "VPN log: Setting underlying network $an" }
                setUnderlyingNetworks(arrayOf(an))
            }
        } else {
            logcat { "VPN log: NetworkSwitchHandling disabled...skip setting underlying network" }
        }

        logcat { "VPN log: Enable new error handling for onStartVpn" }
        vpnNetworkStack.onStartVpn(activeTun!!).getOrElse {
            logcat(ERROR) { "VPN log: Failed to start VPN" }
            stopVpn(VpnStopReason.ERROR, false)
            return@withContext
        }

        vpnServiceCallbacksPluginPoint.getPlugins().forEach {
            if (restarting) {
                logcat { "VPN log: onVpnReconfigured ${it.javaClass} callback" }
                it.onVpnReconfigured(this)
            } else {
                logcat { "VPN log: onVpnStarted ${it.javaClass} callback" }
                it.onVpnStarted(this)
            }
        }

        Intent(applicationContext, VpnStateMonitorService::class.java).also {
            bindService(it, vpnStateServiceConnection, Context.BIND_AUTO_CREATE)
        }

        // lastly set the VPN state to enabled
        vpnServiceStateStatsDao.insert(createVpnState(state = ENABLED))

        alwaysOnStateJob += launch { monitorVpnAlwaysOnState() }
    }

    private fun createNullRouteTempTunnel(): ParcelFileDescriptor? {
        checkMainThread()

        return Builder().run {
            allowFamily(AF_INET6)
            addAddress(InetAddress.getByName("10.0.0.2"), 32)
            addAddress(InetAddress.getByName("fd00:1:fd00:1:fd00:1:fd00:1"), 32)
            // nobody will be listening here we just want to make sure no app has connection
            addDnsServer("10.0.0.1")
            // just so that we can connect to our BE
            // TODO should we protect all comms with our controller BE? other VPNs do that
            safelyAddDisallowedApps(listOf("com.duckduckgo.mobile.android", "com.duckduckgo.mobile.android.debug"))
            setBlocking(true)
            setMtu(1280)
            prepare(this@TrackerBlockingVpnService)
            establish()
        }.also {
            logcat { "VPN log: Hole TUN created ${it?.fd}" }
        }
    }

    private suspend fun createTunnelInterface(
        tunnelConfig: VpnTunnelConfig,
    ): ParcelFileDescriptor? {
        val tunInterface = Builder().run {
            tunnelConfig.addresses.forEach { addAddress(it.key, it.value) }
            val tunHasIpv6Address = tunnelConfig.addresses.any { it.key is Inet6Address }

            // Allow IPv6 to go through the VPN
            // See https://developer.android.com/reference/android/net/VpnService.Builder#allowFamily(int) for more info as to why
            if (tunHasIpv6Address) {
                logcat { "VPN log: Allowing IPv6 traffic through the tun interface" }
                allowFamily(AF_INET6)
            }

            val systemDnsList = getSystemDns()

            // TODO: eventually routes will be set by remote config
            if (appBuildConfig.isPerformanceTest && appBuildConfig.isInternalBuild()) {
                // Currently allowing host PC address 10.0.2.2 when running performance test on an emulator (normally we don't route local traffic)
                // The address is also isolated to minimize network interference during performance tests
                VpnRoutes.includedTestRoutes.forEach { addRoute(it.address, it.maskWidth) }
            } else {
                // If tunnel config has routes use them, else use the defaults.
                // we're mapping her to a list of pairs because we want to make sure that when we combine them with other defaults, eg. add the DNS
                // addresses, we don't override any entry in the map
                val vpnRoutes = tunnelConfig.routes
                    .map { it.key to it.value }
                    .ifEmpty { VpnRoutes.includedRoutes.asAddressMaskPair() }.toMutableList()

                // Any DNS that comes from the tunnel config shall be added to the routes
                // TODO filtering Ipv6 out for now for simplicity. Once we support IPv6 we'll come back to this
                tunnelConfig.dns.filterIsInstance<Inet4Address>().forEach { dns ->
                    dns.asRoute()?.let {
                        logcat { "VPN log: Adding tunnel config DNS address $it to VPN routes" }
                        vpnRoutes.add(it.address to it.maskWidth)
                    }
                }

                if (isInterceptDnsTrafficEnabled) {
                    // we need to make sure that all System DNS traffic goes through the VPN. Specifically when the DNS server is on the local network
                    systemDnsList.filterIsInstance<Inet4Address>().forEach { addr ->
                        addr.asRoute()?.let {
                            logcat { "VPN log: Adding DNS address $it to VPN routes" }
                            vpnRoutes.add(it.address to it.maskWidth)
                        }
                    }
                }

                vpnRoutes.mapNotNull { runCatching { InetAddress.getByName(it.first) to it.second }.getOrNull() }
                    .filter { (it.first is Inet4Address) || (tunHasIpv6Address && it.first is Inet6Address) }
                    .forEach { route ->
                        if (!route.first.isLoopbackAddress) {
                            logcat { "Adding route $route" }
                            runCatching {
                                addRoute(route.first, route.second)
                            }.onFailure {
                                logcat(LogPriority.WARN) { "VPN log: Error setting route $route: ${it.asLog()}" }
                            }
                        } else {
                            logcat(LogPriority.WARN) { "VPN log: Tried to add loopback address $route to VPN routes" }
                        }
                    }
            }

            // Add the route for all Global Unicast Addresses. This is the IPv6 equivalent to
            // IPv4 public IP addresses. They are addresses that routable in the internet
            if (tunHasIpv6Address) {
                logcat { "VPN log: Setting IPv6 address in the tun interface" }
                addRoute("2000::", 3)
            }

            setBlocking(true)
            // Cap the max MTU value to avoid backpressure issues in the socket
            // This is effectively capping the max segment size too
            setMtu(tunnelConfig.mtu)
            configureMeteredConnection()

            // Set DNS
            (systemDnsList + tunnelConfig.dns)
                .filter { (it is Inet4Address) || (tunHasIpv6Address && it is Inet6Address) }
                .forEach { addr ->
                    logcat { "VPN log: Adding DNS $addr" }
                    runCatching {
                        addDnsServer(addr)
                    }.onFailure { t ->
                        logcat(ERROR) { "VPN log: Error setting DNS $addr: ${t.asLog()}" }
                        if (addr.isLoopbackAddress) {
                            deviceShieldPixels.reportLoopbackDnsError()
                        } else if (addr.isAnyLocalAddress) {
                            deviceShieldPixels.reportAnylocalDnsError()
                        } else {
                            deviceShieldPixels.reportGeneralDnsError()
                        }
                    }
                }

            safelyAddDisallowedApps(tunnelConfig.appExclusionList.toList())

            // Apparently we always need to call prepare, even tho not clear in docs
            // without this prepare, establish() returns null after device reboot
            prepare(this@TrackerBlockingVpnService.applicationContext)
            establish()
        }

        if (tunInterface == null) {
            logcat(ERROR) { "VPN log: Failed to establish VPN tunnel" }
            stopVpn(VpnStopReason.ERROR, false)
        } else {
            logcat { "VPN log: Final TUN interface created ${tunInterface.fd}" }
        }

        return tunInterface
    }

    /**
     * @return the DNS configured in the Android System
     */
    private fun getSystemDns(): Set<InetAddress> {
        // private extension function, this is purposely here to limit visibility
        fun Set<InetAddress>.containsIpv4(): Boolean {
            forEach {
                if (it is Inet4Address) return true
            }
            return false
        }

        val dns = mutableSetOf<InetAddress>()

        // System DNS
        if (isInterceptDnsTrafficEnabled) {
            kotlin.runCatching {
                applicationContext.getSystemActiveNetworkDefaultDns()
                    .map { InetAddress.getByName(it) }
            }.getOrNull()?.run {
                for (inetAddress in this) {
                    if (!dns.contains(inetAddress) && !(inetAddress.isLocal())) {
                        dns.add(inetAddress)
                    }
                }
            }
        }

        // Android Private DNS (added by the user)
        if (isPrivateDnsSupportEnabled && vpnPreferences.isPrivateDnsEnabled) {
            runCatching {
                InetAddress.getAllByName(applicationContext.getPrivateDnsServerName())
            }.getOrNull()?.run { dns.addAll(this) }
        }

        // This is purely internal, never to go to production
        if (appBuildConfig.isInternalBuild() && isAlwaysSetDNSEnabled) {
            if (dns.isEmpty()) {
                kotlin.runCatching {
                    logcat { "VPN log: Adding cloudflare DNS" }
                    dns.add(InetAddress.getByName("1.1.1.1"))
                    dns.add(InetAddress.getByName("1.0.0.1"))
                    dns.add(InetAddress.getByName("2606:4700:4700::1111"))
                    dns.add(InetAddress.getByName("2606:4700:4700::1001"))
                }.onFailure {
                    logcat(LogPriority.WARN) { "VPN log: Error adding fallback DNS: ${it.asLog()}" }
                }
            }

            // always add ipv4 DNS
            if (!dns.containsIpv4()) {
                logcat { "VPN log: DNS set does not contain IPv4, adding cloudflare" }
                kotlin.runCatching {
                    dns.add(InetAddress.getByName("1.1.1.1"))
                    dns.add(InetAddress.getByName("1.0.0.1"))
                }.onFailure {
                    logcat(LogPriority.WARN) { "VPN log: Error adding fallback DNS ${it.asLog()}" }
                }
            }
        }

        if (!dns.containsIpv4()) {
            // never allow IPv6-only DNS
            logcat { "VPN log: No IPv4 DNS found, return empty DNS list" }
            return setOf()
        }

        return dns.toSet()
    }

    private fun Builder.safelyAddDisallowedApps(apps: List<String>) {
        for (app in apps) {
            try {
                logcat { "VPN log: Excluding app from VPN: $app" }
                addDisallowedApplication(app)
            } catch (e: PackageManager.NameNotFoundException) {
                logcat(LogPriority.WARN) { "VPN log: Package name not found: $app" }
            }
        }
    }

    private suspend fun stopVpn(
        reason: VpnStopReason,
        hasVpnAlreadyStarted: Boolean = true,
    ) = withContext(serviceDispatcher) {
        logcat { "VPN log: Stopping VPN. $reason" }

        vpnNetworkStack.onStopVpn(reason)

        activeTun = null

        alwaysOnStateJob.cancel()

        sendStopPixels(reason)

        // If VPN has been started, then onVpnStopped must be called. Else, an error might have occurred before start so we call onVpnStartFailed
        if (hasVpnAlreadyStarted) {
            vpnServiceCallbacksPluginPoint.getPlugins().forEach {
                logcat { "VPN log: stopping ${it.javaClass} callback" }
                it.onVpnStopped(this, reason)
            }
        } else {
            vpnServiceCallbacksPluginPoint.getPlugins().forEach {
                logcat { "VPN log: onVpnStartFailed ${it.javaClass} callback" }
                it.onVpnStartFailed(this)
            }
        }

        vpnStateServiceReference?.let {
            runCatching { unbindService(vpnStateServiceConnection).also { vpnStateServiceReference = null } }
        }

        // Set the state to DISABLED here, then call the on stop/failure callbacks
        vpnServiceStateStatsDao.insert(createVpnState(state = VpnServiceState.DISABLED, stopReason = reason))

        stopForeground(true)
        stopSelf()
    }

    private fun sendStopPixels(reason: VpnStopReason) {
        when (reason) {
            VpnStopReason.SELF_STOP, VpnStopReason.RESTART, VpnStopReason.UNKNOWN -> {} // no-op
            VpnStopReason.ERROR -> deviceShieldPixels.startError()
            VpnStopReason.REVOKED -> deviceShieldPixels.suddenKillByVpnRevoked()
        }
    }

    @Suppress("NewApi") // we use appBuildConfig
    private fun VpnService.Builder.configureMeteredConnection() {
        if (appBuildConfig.sdkInt >= Build.VERSION_CODES.Q) {
            setMetered(false)
        }
    }

    override fun onRevoke() {
        logcat(LogPriority.WARN) { "VPN log: onRevoke called" }
        launch { stopVpn(VpnStopReason.REVOKED) }
    }

    override fun onLowMemory() {
        logcat(LogPriority.WARN) { "VPN log: onLowMemory called" }
    }

    // https://developer.android.com/reference/android/app/Service.html#onTrimMemory(int)
    override fun onTrimMemory(level: Int) {
        logcat { "VPN log: onTrimMemory level $level called" }

        // Collect memory data info from memory collectors
        val memoryData = mutableMapOf<String, String>()
        memoryCollectorPluginPoint.getPlugins().forEach { memoryData.putAll(it.collectMemoryMetrics()) }

        if (memoryData.isEmpty()) {
            logcat { "VPN log: nothing to send from memory collectors" }
            return
        }

        when (level) {
            TRIM_MEMORY_BACKGROUND -> deviceShieldPixels.vpnProcessExpendableLow(memoryData)
            TRIM_MEMORY_MODERATE -> deviceShieldPixels.vpnProcessExpendableModerate(memoryData)
            TRIM_MEMORY_COMPLETE -> deviceShieldPixels.vpnProcessExpendableComplete(memoryData)
            TRIM_MEMORY_RUNNING_MODERATE -> deviceShieldPixels.vpnMemoryRunningModerate(memoryData)
            TRIM_MEMORY_RUNNING_LOW -> deviceShieldPixels.vpnMemoryRunningLow(memoryData)
            TRIM_MEMORY_RUNNING_CRITICAL -> deviceShieldPixels.vpnMemoryRunningCritical(memoryData)
            else -> {} // no-op
        }
    }

    private fun notifyVpnStart() {
        val vpnNotification =
            vpnEnabledNotificationContentPluginPoint.getHighestPriorityPlugin()?.getInitialContent()
                ?: VpnEnabledNotificationContentPlugin.VpnEnabledNotificationContent.EMPTY

        startForeground(
            VPN_FOREGROUND_SERVICE_ID,
            VpnEnabledNotificationBuilder.buildVpnEnabledNotification(applicationContext, vpnNotification),
        )
    }

    private suspend fun monitorVpnAlwaysOnState() = withContext(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
        suspend fun incrementalPeriodicChecks(
            times: Int = Int.MAX_VALUE,
            initialDelay: Long = 500, // 0.5 second
            maxDelay: Long = 300_000, // 5 minutes
            factor: Double = 1.05, // 5% increase
            block: suspend () -> Unit,
        ) {
            var currentDelay = initialDelay
            repeat(times - 1) {
                try {
                    if (isActive) block()
                } catch (t: Throwable) {
                    // you can log an error here and/or make a more finer-grained
                    // analysis of the cause to see if retry is needed
                }
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
            }
        }

        val vpnState = createVpnState(ENABLED)

        @SuppressLint("NewApi") // IDE doesn't get we use appBuildConfig
        if (appBuildConfig.sdkInt >= 29) {
            incrementalPeriodicChecks {
                if (vpnServiceStateStatsDao.getLastStateStats()?.state == ENABLED) {
                    if (vpnState.alwaysOnState.alwaysOnEnabled) deviceShieldPixels.reportAlwaysOnEnabledDaily()
                    if (vpnState.alwaysOnState.alwaysOnLockedDown) deviceShieldPixels.reportAlwaysOnLockdownEnabledDaily()

                    vpnServiceStateStatsDao.insert(vpnState).also { logcat { "state: $vpnState" } }
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun createVpnState(
        state: VpnServiceState,
        stopReason: VpnStopReason = VpnStopReason.UNKNOWN,
    ): VpnServiceStateStats {
        fun VpnStopReason.asVpnStoppingReason(): VpnStoppingReason {
            return when (this) {
                VpnStopReason.RESTART -> VpnStoppingReason.RESTART
                VpnStopReason.SELF_STOP -> VpnStoppingReason.SELF_STOP
                VpnStopReason.REVOKED -> VpnStoppingReason.REVOKED
                VpnStopReason.ERROR -> VpnStoppingReason.ERROR
                VpnStopReason.UNKNOWN -> VpnStoppingReason.UNKNOWN
            }
        }

        val isAlwaysOnEnabled = if (appBuildConfig.sdkInt >= 29) isAlwaysOn else false
        val isLockdownEnabled = if (appBuildConfig.sdkInt >= 29) isLockdownEnabled else false

        return VpnServiceStateStats(
            state = state,
            alwaysOnState = AlwaysOnState(isAlwaysOnEnabled, isLockdownEnabled),
            stopReason = stopReason.asVpnStoppingReason(),
        )
    }

    companion object {
        const val ACTION_VPN_REMINDER_RESTART = "com.duckduckgo.vpn.internaltesters.reminder.restart"

        const val VPN_REMINDER_NOTIFICATION_ID = 999
        const val VPN_FOREGROUND_SERVICE_ID = 200

        private fun serviceIntent(context: Context): Intent {
            return Intent(context, TrackerBlockingVpnService::class.java)
        }

        private fun startIntent(context: Context): Intent {
            return serviceIntent(context).also {
                it.action = ACTION_START_VPN
            }
        }

        private fun stopIntent(context: Context): Intent {
            return serviceIntent(context).also {
                it.action = ACTION_STOP_VPN
            }
        }

        private fun restartIntent(context: Context): Intent {
            return serviceIntent(context).also {
                it.action = ACTION_RESTART_VPN
            }
        }

        // This method was deprecated in API level 26. As of Build.VERSION_CODES.O,
        // this method is no longer available to third party applications.
        // For backwards compatibility, it will still return the caller's own services.
        // So for us it's still valid because we don't need to know third party services, just ours.
        @Suppress("DEPRECATION")
        internal fun isServiceRunning(context: Context): Boolean {
            val manager = kotlin.runCatching {
                context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            }.getOrElse {
                return false
            }

            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (TrackerBlockingVpnService::class.java.name == service.service.className) {
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M) {
                        return service.started
                    }
                    return true
                }
            }
            return false
        }

        internal fun startService(context: Context) {
            startVpnService(context.applicationContext)
        }

        // TODO commented out for now, we'll see if we need it once we enable the new networking layer
//        private fun startTrampolineService(context: Context) {
//            val applicationContext = context.applicationContext
//
//            if (isServiceRunning(applicationContext)) return
//
//            runCatching {
//                Intent(applicationContext, VpnTrampolineService::class.java).also { i ->
//                    applicationContext.startService(i)
//                }
//            }.onFailure {
//                // fallback for when both browser and vpn processes are not up, as we can't start a non-foreground service in the background
//                Timber.w(it, "VPN log: Failed to start trampoline service")
//                startVpnService(applicationContext)
//            }
//        }

        internal fun startVpnService(context: Context) {
            val applicationContext = context.applicationContext

            if (isServiceRunning(applicationContext)) return

            startIntent(applicationContext).run {
                ContextCompat.startForegroundService(applicationContext, this)
            }
        }

        internal fun stopService(context: Context) {
            val applicationContext = context.applicationContext

            if (!isServiceRunning(applicationContext)) return

            stopIntent(applicationContext).run {
                ContextCompat.startForegroundService(applicationContext, this)
            }
        }

        private fun restartService(context: Context) {
            val applicationContext = context.applicationContext

            restartIntent(applicationContext).run {
                ContextCompat.startForegroundService(applicationContext, this)
            }
        }

        internal fun restartVpnService(
            context: Context,
            forceGc: Boolean = false,
            forceRestart: Boolean = false,
        ) {
            val applicationContext = context.applicationContext
            if (isServiceRunning(applicationContext)) {
                restartService(applicationContext)

                if (forceGc) {
                    logcat { "VPN log: Forcing a garbage collection to run while VPN is restarting" }
                    System.gc()
                }
            } else if (forceRestart) {
                logcat { "VPN log: starting service" }
                startVpnService(applicationContext)
            }
        }

        private const val ACTION_START_VPN = "ACTION_START_VPN"
        private const val ACTION_STOP_VPN = "ACTION_STOP_VPN"
        private const val ACTION_RESTART_VPN = "ACTION_RESTART_VPN"
        private const val ACTION_ALWAYS_ON_START = "android.net.VpnService"
    }

    private fun VpnNetworkStack.onCreateVpnWithErrorReporting() {
        if (this.onCreateVpn().isFailure) {
            logcat { "VPN log: error creating the VPN network ${this.name}" }
            // report and proceed
            deviceShieldPixels.reportErrorCreatingVpnNetworkStack()
        } else {
            logcat { "VPN log: VPN network ${this.name} created" }
        }
    }
}

@Module
@ContributesTo(VpnScope::class)
abstract class VpnSocketProtectorModule {
    @Binds
    abstract fun bindVpnSocketProtector(impl: TrackerBlockingVpnService): VpnSocketProtector
}
