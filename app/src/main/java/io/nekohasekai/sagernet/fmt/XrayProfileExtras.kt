package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.DnsObject

// Helpers for the parts of an imported Xray JSON profile that are kept beside its outbounds:
// the observatory probe settings and dns.hosts. Kept free of Android dependencies.

private val durationPart = Regex("""(\d+(?:\.\d*)?|\.\d+)(ns|us|µs|μs|ms|s|m|h)""")

/**
 * Parses a Go/Xray duration such as "30s", "5m", "1m30s", "1.5h" or "500ms" into whole
 * seconds (at least 1). A bare number is taken as seconds. Returns null when unparsable.
 */
fun parseXrayDurationSeconds(value: String): Int? {
    val text = value.trim().lowercase()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { return clampSeconds(it) }
    var seconds = 0.0
    var consumed = 0
    for (match in durationPart.findAll(text)) {
        if (match.range.first != consumed) return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        seconds += amount * when (match.groupValues[2]) {
            "ns" -> 1e-9
            "us", "µs", "μs" -> 1e-6
            "ms" -> 1e-3
            "m" -> 60.0
            "h" -> 3600.0
            else -> 1.0
        }
        consumed = match.range.last + 1
    }
    if (consumed == 0 || consumed != text.length) return null
    return clampSeconds(seconds)
}

private fun clampSeconds(seconds: Double): Int? {
    if (seconds.isNaN() || seconds < 0) return null
    return seconds.toLong().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
}

private fun JsonObject.member(key: String): JsonElement? {
    get(key)?.let { return it }
    return entrySet().firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
}

private fun JsonObject.memberObject(key: String) = member(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.memberString(vararg keys: String): String? {
    for (key in keys) {
        val value = member(key) ?: continue
        if (value.isJsonPrimitive) return value.asString.trim().takeIf { it.isNotEmpty() }
    }
    return null
}

/**
 * Probe URL and interval (seconds) of an Xray config: the top-level `observatory`, or the
 * `burstObservatory`'s `pingConfig` when there is no observatory. Null where unset.
 */
fun extractXrayProbeSettings(config: JsonObject): Pair<String?, Int?> {
    config.memberObject("observatory")?.let { observatory ->
        val url = observatory.memberString("probeUrl", "probeURL")
        val interval = observatory.memberString("probeInterval")?.let { parseXrayDurationSeconds(it) }
        return url to interval
    }
    val ping = config.memberObject("burstObservatory")?.memberObject("pingConfig") ?: return null to null
    val url = ping.memberString("destination")
    val interval = ping.memberString("interval")?.let { parseXrayDurationSeconds(it) }
    return url to interval
}

/**
 * The config's `dns.hosts`, normalized to a JSON object of domain to address string or
 * address list (Xray accepts both), or "" when it has none.
 */
fun extractXrayDnsHosts(config: JsonObject): String {
    val hosts = config.memberObject("dns")?.memberObject("hosts") ?: return ""
    val result = JsonObject()
    for ((domain, value) in hosts.entrySet()) {
        val key = domain.trim()
        if (key.isEmpty()) continue
        val addresses = hostAddresses(value)
        when (addresses.size) {
            0 -> continue
            1 -> result.addProperty(key, addresses[0])
            else -> result.add(key, JsonArray().apply { addresses.forEach { add(it) } })
        }
    }
    return if (result.size() == 0) "" else result.toString()
}

private fun hostAddresses(value: JsonElement): List<String> = when {
    value.isJsonPrimitive -> listOfNotNull(value.asString.trim().takeIf { it.isNotEmpty() })
    value.isJsonArray -> value.asJsonArray
        .filter { it.isJsonPrimitive }
        .mapNotNull { it.asString.trim().takeIf { s -> s.isNotEmpty() } }
        .distinct()
    else -> emptyList()
}

/**
 * Adds the entries of a profile's stored dns.hosts ([AbstractBean.profileDnsHosts]) to
 * [hosts]. Domains already present -- the user's own hosts, or an earlier profile's -- are
 * left untouched. Malformed input is ignored.
 */
fun mergeProfileDnsHosts(hosts: MutableMap<String, DnsObject.StringOrListObject>, profileDnsHosts: String) {
    if (profileDnsHosts.isBlank()) return
    val parsed = try {
        JsonParser.parseString(profileDnsHosts)
    } catch (_: Exception) {
        return
    }
    if (!parsed.isJsonObject) return
    for ((domain, value) in parsed.asJsonObject.entrySet()) {
        if (domain.isEmpty() || hosts.containsKey(domain)) continue
        val addresses = hostAddresses(value)
        if (addresses.isEmpty()) continue
        hosts[domain] = DnsObject.StringOrListObject().apply {
            if (addresses.size == 1) {
                valueX = addresses[0]
                valueY = null
            } else {
                valueX = null
                valueY = addresses
            }
        }
    }
}
