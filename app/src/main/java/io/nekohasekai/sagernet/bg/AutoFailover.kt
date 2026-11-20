package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.test.V2RayTestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Periodically checks the active profile and, after [FAILURES_BEFORE_SWITCH] failed checks in a row,
 * switches to the fastest reachable profile of the same group. The restart goes through
 * stopRunner(restart = true), so the VPN blackhole keeps traffic from leaking during the switch.
 */
object AutoFailover {

    private const val FAILURES_BEFORE_SWITCH = 2

    @OptIn(DelicateCoroutinesApi::class)
    fun schedule(service: BaseService.Interface, profile: ProxyEntity) {
        val data = service.data
        data.failoverJob?.cancel()
        val interval = DataStore.autoFailoverInterval
        if (interval <= 0 || profile.type == ProxyEntity.TYPE_BALANCER) return
        data.failoverJob = GlobalScope.launch(Dispatchers.Default) {
            var failures = 0
            while (isActive) {
                delay(interval.coerceAtLeast(10) * 1000L)
                if (data.state != BaseService.State.Connected || DataStore.startedProfile != profile.id) return@launch
                val alive = try {
                    data.binder.urlTest() > 0
                } catch (e: Exception) {
                    false
                }
                if (alive) {
                    failures = 0
                    continue
                }
                if (++failures < FAILURES_BEFORE_SWITCH) continue
                failures = 0
                val best = findFastest(profile) ?: continue // nothing reachable, probably no network at all
                if (data.state != BaseService.State.Connected || DataStore.startedProfile != profile.id) return@launch
                Logs.i("auto failover: ${profile.displayName()} -> ${best.displayName()}")
                DataStore.selectedProxy = best.id
                onMainDispatcher {
                    service.stopRunner(restart = true)
                }
                return@launch
            }
        }
    }

    // browser-forwarded transports can't be tested headlessly (same rule as the manual URL test)
    private fun ProxyEntity.needsBrowser(): Boolean = when (val bean = requireBean()) {
        is StandardV2RayBean -> when (bean.type) {
            "ws" -> bean.wsUseBrowserForwarder
            "splithttp" -> bean.shUseBrowserForwarder
            else -> false
        }
        else -> false
    }

    /**
     * URL test of [profile] through a throwaway core instance; ping in ms, or 0 if it failed.
     * With the system TUN stack the test's sockets have to be protected from our own VPN.
     */
    suspend fun ping(profile: ProxyEntity, vpnRunning: Boolean = SagerNet.started && DataStore.startedProfile > 0): Int {
        val protectPath = if (vpnRunning && DataStore.tunImplementation == TunImplementation.SYSTEM && DataStore.serviceMode == Key.MODE_VPN) {
            SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
        } else ""
        return try {
            V2RayTestInstance(profile, DataStore.connectionTestURL, DataStore.connectionTestTimeout.coerceIn(1, 30) * 1000, protectPath)
                .use { it.doTest() }
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun findFastest(current: ProxyEntity): ProxyEntity? {
        val candidates = SagerDatabase.proxyDao.getByGroup(current.groupId).filter {
            it.id != current.id && !it.hidden && !it.needsBrowser() &&
                it.type != ProxyEntity.TYPE_BALANCER && it.type != ProxyEntity.TYPE_CHAIN
        }
        if (candidates.isEmpty()) return null
        val semaphore = Semaphore(DataStore.connectionTestConcurrency.coerceIn(1, 64))
        return coroutineScope {
            candidates.map { candidate ->
                async {
                    semaphore.withPermit {
                        candidate to ping(candidate, vpnRunning = true)
                    }
                }
            }.awaitAll()
        }.filter { it.second > 0 }.minByOrNull { it.second }?.first
    }
}
