package io.nekohasekai.sagernet.utils

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libexclavecore.HTTPClient
import libexclavecore.Libexclavecore
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves server hostnames for display purposes (flags, ASN, mock location) without
 * leaking them to the system resolver. The app is excluded from its own VPN, so
 * InetAddress.getByName() would send every server domain to the ISP in plaintext,
 * even while connected.
 *
 * Uses DoH addressed by IP (no plaintext lookup of the DoH server itself), routed
 * through the running proxy when there is one.
 */
object HostResolver {

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    private val endpoints = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
    )

    private val cache = ConcurrentHashMap<String, String>()

    private val ipv4Regex = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")

    fun isIpLiteral(s: String): Boolean = s.isNotEmpty() && (s.matches(ipv4Regex) || s.contains(':'))

    /** Last successful resolution for [host], or [host] itself if it is an IP literal. */
    fun cached(host: String): String? = if (isIpLiteral(host)) host else cache[host]

    suspend fun resolve(host: String): String? {
        cached(host)?.let { return it }
        return withContext(Dispatchers.IO) {
            val client = newClient()
            try {
                val ip = query(client, host, TYPE_A) ?: query(client, host, TYPE_AAAA)
                // failures are not cached, so a lookup made while offline is retried later
                if (ip != null) cache[host] = ip
                ip
            } finally {
                client.close()
            }
        }
    }

    fun clear() = cache.clear()

    /** HTTP client that goes through the proxy when one is running. */
    fun newClient(): HTTPClient = Libexclavecore.newHttpClient().apply {
        keepAlive()
        if (SagerNet.started && DataStore.startedProfile > 0) {
            useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
        }
    }

    private fun query(client: HTTPClient, host: String, type: Int): String? {
        for (endpoint in endpoints) {
            try {
                val response = client.newRequest().apply {
                    setURL("$endpoint?name=$host&type=$type")
                    setHeader("Accept", "application/dns-json")
                }.execute()
                val json = JsonParser.parseString(response.contentString).asJsonObject
                // a non-zero status (e.g. NXDOMAIN) is an authoritative answer, don't ask the next server
                if (json.get("Status")?.asInt != 0) return null
                return json.getAsJsonArray("Answer")
                    ?.map { it.asJsonObject }
                    ?.firstOrNull { it.get("type")?.asInt == type }
                    ?.get("data")?.asString
            } catch (e: Throwable) {
                Logs.w(e)
            }
        }
        return null
    }

}
