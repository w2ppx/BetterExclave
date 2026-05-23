/******************************************************************************
 *                                                                            *
 * Copyright (C) 2024  dyhkwong                                               *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.group

import androidx.core.net.toUri
import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocksConfig
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardConfig
import io.nekohasekai.sagernet.ktx.*
import libsagernetcore.Libsagernetcore
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.util.regex.Pattern

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    data class BalancerSpec(
        val name: String,
        val memberBeans: List<AbstractBean>,
        val allEntryBeans: List<AbstractBean>,
        val strategy: String,
        val routingRules: String,
    )

    private val pendingBalancers = ThreadLocal.withInitial { mutableListOf<BalancerSpec>() }
    private val pendingBalancerMembers = ThreadLocal.withInitial {
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AbstractBean, Boolean>())
    }

    private fun pendingBalancerSpecs() = pendingBalancers.get()!!
    private fun pendingBalancerMemberBeans() = pendingBalancerMembers.get()!!
    private fun resetBalancerSpecs() {
        pendingBalancerSpecs().clear()
        pendingBalancerMemberBeans().clear()
    }
    private fun addBalancerSpec(spec: BalancerSpec) {
        pendingBalancerSpecs().add(spec)
        pendingBalancerMemberBeans().addAll(spec.allEntryBeans)
    }
    private fun protectBalancerEntryBeans(beans: Collection<AbstractBean>) {
        pendingBalancerMemberBeans().addAll(beans)
    }
    private fun consumeBalancerSpecs(): List<BalancerSpec> {
        val r = pendingBalancerSpecs().toList()
        pendingBalancerSpecs().clear()
        return r
    }
    private fun balancerMemberBeans(): Set<AbstractBean> = pendingBalancerMemberBeans()

    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        resetBalancerSpecs()
        val link = subscription.link
        var proxies: List<AbstractBean>
        if (link.startsWith("content://", ignoreCase = true)) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.bufferedReader()
                ?.readText()

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {
            val response = Libsagernetcore.newHttpClient().apply {
                if (SagerNet.started && DataStore.startedProfile > 0) {
                    useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
                }
            }.newRequest().apply {
                setURL(subscription.link)
                if (subscription.happSpoof) {
                    HappSpoof.apply(this, subscription)
                } else if (subscription.customUserAgent.isNotEmpty()) {
                    setUserAgent(subscription.customUserAgent)
                } else {
                    setUserAgent(USER_AGENT)
                }
            }.execute()

            val body = if (response.getHeader("Content-Encoding").equals("gzip", ignoreCase = true)) {
                try {
                    java.util.zip.GZIPInputStream(response.content.inputStream())
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (_: Exception) {
                    response.contentString
                }
            } else {
                response.contentString
            }

            proxies = parseRaw(body)
                ?: error(app.getString(R.string.no_proxies_found))

            val profileTitle = response.getHeader("profile-title").ifEmpty {
                response.getHeader("Content-Disposition")
                    .substringAfter("filename=", "")
                    .substringBefore(';')
                    .trim()
                    .trim('"')
            }
            if (profileTitle.isNotEmpty()) {
                val decoded = if (profileTitle.startsWith("base64:")) {
                    try {
                        profileTitle.removePrefix("base64:").decodeBase64()
                    } catch (_: Exception) {
                        ""
                    }
                } else {
                    profileTitle
                }
                if (decoded.isNotEmpty()) {
                    val current = proxyGroup.name
                    val placeholder = app.getString(R.string.subscription)
                    if (current.isNullOrBlank() || current == placeholder) {
                        proxyGroup.name = decoded
                    }
                }
            }

            val subscriptionUserinfo = response.getHeader("Subscription-Userinfo")
            if (subscriptionUserinfo.isNotEmpty()) {
                fun get(regex: String): String? {
                    return regex.toRegex().findAll(subscriptionUserinfo).mapNotNull {
                        if (it.groupValues.size > 1) it.groupValues[1] else null
                    }.firstOrNull()
                }
                var used = 0L
                try {
                    val upload = get("upload=([0-9]+)")?.toLong() ?: -1L
                    if (upload > 0L) {
                        used += upload
                    }
                    val download = get("download=([0-9]+)")?.toLong() ?: -1L
                    if (download > 0L) {
                        used += download
                    }
                    val total = get("total=([0-9]+)")?.toLong() ?: -1L
                    subscription.apply {
                        if (upload > 0L || download > 0L) {
                            bytesUsed = used
                            bytesRemaining = if (total > 0L) total - used else -1L
                        } else {
                            bytesUsed = -1L
                            bytesRemaining = -1L
                        }
                        expiryDate = get("expire=([0-9]+)")?.toLong() ?: -1L
                    }
                } catch (_: Exception) {
                }
            } else {
                subscription.apply {
                    bytesUsed = -1L
                    bytesRemaining = -1L
                    expiryDate = -1L
                }
            }
        }

        proxies.forEach { it.applyDefaultValues() }

        if (subscription.nameFilter.isNotEmpty()) {
            val pattern = Regex(subscription.nameFilter)
            proxies = proxies.filter { !pattern.containsMatchIn(it.name) }
        }
        if (subscription.nameFilter1.isNotEmpty()) {
            val pattern = Regex(subscription.nameFilter1)
            proxies = proxies.filter { pattern.containsMatchIn(it.name) }
        }

        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                println("Exists name: $name")
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
            .filter { it.type != ProxyEntity.TYPE_BALANCER }
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            data class DedupEntry(
                val bean: AbstractBean,
                val name: String,
                val index: Int,
                var reported: Boolean = false,
            )

            val balancerProtected = balancerMemberBeans()
            val uniqueProxies = LinkedHashMap<Protocols.Deduplication, DedupEntry>()
            val keptBalancerMembers = ArrayList<AbstractBean>()
            for (p in proxies) {
                if (p in balancerProtected) {
                    keptBalancerMembers.add(p)
                    continue
                }
                val proxy = Protocols.Deduplication(p, p.javaClass.toString())
                val existing = uniqueProxies[proxy]
                if (existing != null) {
                    if (!existing.reported) {
                        val name = existing.name.replace(" (${existing.index})", "")
                        if (name.isNotEmpty()) {
                            duplicate.add("$name (${existing.index})")
                            existing.reported = true
                        }
                    }
                    duplicate.add(p.displayName() + " (${existing.index})")
                } else {
                    uniqueProxies[proxy] = DedupEntry(p, p.displayName(), uniqueProxies.size)
                }
            }
            proxies = uniqueProxies.values.map { it.bean } + keptBalancerMembers
        }

        val nameMap = proxies.associateBy { bean ->
            bean.displayName()
        }

        val toDelete = ArrayList<ProxyEntity>()
        val toReplace = exists.mapNotNull { entity ->
            val name = entity.displayName()
            if (nameMap.contains(name)) name to entity else let {
                toDelete.add(entity)
                null
            }
        }.toMap()

        val toUpdate = ArrayList<ProxyEntity>()
        val toAdd = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        var userOrder = 1L
        var changed = toDelete.size
        for ((name, bean) in nameMap.entries) {
            if (toReplace.contains(name)) {
                val entity = toReplace[name]!!
                val existsBean = entity.requireBean()
                existsBean.applyFeatureSettings(bean)
                when {
                    existsBean != bean -> {
                        changed++
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name
                    }
                    entity.userOrder != userOrder -> {
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        entity.userOrder = userOrder
                    }
                }
            } else {
                changed++
                toAdd.add(ProxyEntity(
                    groupId = proxyGroup.id, userOrder = userOrder
                ).apply {
                    putBean(bean)
                })
                added.add(name)
            }
            userOrder++
        }

        val balancerSpecs = consumeBalancerSpecs()
        SagerDatabase.runInTransaction {
            if (toAdd.isNotEmpty()) {
                SagerDatabase.proxyDao.insert(toAdd)
            }
            if (toUpdate.isNotEmpty()) {
                SagerDatabase.proxyDao.updateProxy(toUpdate)
            }
            if (toDelete.isNotEmpty()) {
                SagerDatabase.proxyDao.deleteProxy(toDelete)
            }

            if (balancerSpecs.isNotEmpty()) {
                val currentProxies = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                val currentProxyByName = currentProxies.associateBy { it.displayName() }
                val validBalancerSpecs = balancerSpecs.mapNotNull { spec ->
                    val memberEntities = spec.memberBeans.mapNotNull { currentProxyByName[it.displayName()] }
                    val memberIds = memberEntities.map { it.id }
                    if (memberIds.size < 2) null else Triple(spec, memberEntities, memberIds)
                }
                fun balancerKey(name: String) = name.trim()
                val activeProxies = currentProxies
                val balancerEntitiesByName = activeProxies
                    .filter { it.type == ProxyEntity.TYPE_BALANCER }
                    .associateBy { balancerKey(it.displayName()) }
                val namesToHide = validBalancerSpecs.flatMap { (spec, _, _) ->
                    spec.allEntryBeans.map { it.displayName() }
                }.toSet()
                val balancersToUpdate = ArrayList<ProxyEntity>()
                val balancersToAdd = ArrayList<ProxyEntity>()
                for ((spec, memberEntities, memberIds) in validBalancerSpecs) {
                    val strategy = when (spec.strategy.lowercase()) {
                        "leastload", "leastping" -> spec.strategy
                        "random" -> "random"
                        "roundrobin", "round-robin" -> "roundRobin"
                        else -> "random"
                    }
                    val targetOrder = memberEntities.minOf { it.userOrder }
                    val existing = balancerEntitiesByName[balancerKey(spec.name)]
                    if (existing != null) {
                        val current = existing.balancerBean
                        val needsUpdate = current == null ||
                                current.type != BalancerBean.TYPE_LIST ||
                                current.name != spec.name ||
                                current.strategy != strategy ||
                                current.proxies != memberIds ||
                                current.profileRoutingRules != spec.routingRules ||
                                existing.userOrder != targetOrder
                        if (needsUpdate) {
                            val bb = current ?: BalancerBean().apply {
                                this.type = BalancerBean.TYPE_LIST
                            }
                            bb.name = spec.name
                            bb.strategy = strategy
                            bb.proxies = memberIds
                            bb.profileRoutingRules = spec.routingRules
                            bb.initializeDefaultValues()
                            existing.putBean(bb)
                            existing.userOrder = targetOrder
                            balancersToUpdate.add(existing)
                        }
                    } else {
                        val bb = BalancerBean().apply {
                            this.type = BalancerBean.TYPE_LIST
                            this.name = spec.name
                            this.proxies = memberIds
                            this.strategy = strategy
                            this.profileRoutingRules = spec.routingRules
                            initializeDefaultValues()
                        }
                        balancersToAdd.add(ProxyEntity(
                            groupId = proxyGroup.id, userOrder = targetOrder
                        ).apply { putBean(bb) })
                        added.add(spec.name)
                        changed++
                    }
                }
                if (balancersToUpdate.isNotEmpty()) {
                    SagerDatabase.proxyDao.updateProxy(balancersToUpdate)
                }
                if (balancersToAdd.isNotEmpty()) {
                    SagerDatabase.proxyDao.insert(balancersToAdd)
                }
                val hiddenUpdates = activeProxies
                    .filter { it.type != ProxyEntity.TYPE_BALANCER }
                    .filter { it.hidden != (it.displayName() in namesToHide) }
                    .onEach { it.hidden = it.displayName() in namesToHide }
                if (hiddenUpdates.isNotEmpty()) {
                    SagerDatabase.proxyDao.updateProxy(hiddenUpdates)
                }
            }

            subscription.lastUpdated = System.currentTimeMillis() / 1000
            SagerDatabase.groupDao.updateGroup(proxyGroup)
        }
        finishUpdate(proxyGroup)

        if (byUser && userInterface != null) {
            userInterface.onUpdateSuccess(proxyGroup, changed, added, updated, deleted, duplicate)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseRaw(text: String): List<AbstractBean>? {
        try {
            val options = DumperOptions()
            val yaml = Yaml(YAMLConstructor(LoaderOptions()), Representer(options), options, object : Resolver() {
                override fun addImplicitResolver(tag: Tag, regexp: Pattern, first: String?, limit: Int) {
                    when (tag) {
                        Tag.FLOAT -> {}
                        Tag.BOOL -> super.addImplicitResolver(tag, Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"), "tTfF", limit)
                        else -> super.addImplicitResolver(tag, regexp, first, limit)
                    }
                }
            }).apply {
                // https://github.com/SagerNet/SagerNet/blob/70e684bae81d4bb4203e860ab88c4319e88f944d/app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt#L229
                // IDK why but `!<str>` is obviously widely used in Clash ecology
                // https://github.com/search?q=!%3Cstr%3E&type=code
                // addTypeDescription(TypeDescription(String::class.java, "str"))
            }.loadAs(text, Map::class.java)
            (yaml["proxies"] as? List<Map<String, Any?>>)?.let { proxies ->
                parseClashProxies(proxies).takeIf { it.isNotEmpty() }?.let {
                    return it
                }
            }
        } catch (_: Exception) {}
        try {
            parseJSONConfig(text).takeIf { it.isNotEmpty() }?.let {
                return it
            }
        } catch (_: Exception) {}
        try {
            parseShareLinks(text.decodeBase64()).takeIf { it.isNotEmpty() }?.let {
                return it
            }
        } catch (_: Exception) {}
        try {
            parseShareLinks(text).takeIf { it.isNotEmpty() }?.let {
                return it
            }
        } catch (_: Exception) {}
        try {
            parseWireGuardConfig(text).takeIf { it.isNotEmpty() }?.let {
                return it
            }
        } catch (_: Exception) {}
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJSONConfig(text: String): List<AbstractBean> {
        val jsonElement = parseJson(stripJson(text, stripTrailingCommas = true))
        if (jsonElement.isJsonArray) {
            // https://github.com/XTLS/Xray-core/discussions/3765 WTF
            val beans = ArrayList<AbstractBean>()
            jsonElement.asJsonArray.forEach { entry ->
                if (!entry.isJsonObject) {
                    return listOf()
                }
                val obj = entry.asJsonObject
                val prefix = (obj.getString("remarks", ignoreCase = true) ?: "").trim()
                val profileRoutingRules = extractRoutingRules(obj)

                val outboundsArr = obj.getArray("outbounds", ignoreCase = true) ?: return@forEach
                val proxyOutbounds = outboundsArr.filter { ob ->
                    val proto = ob.asJsonObject?.getString("protocol")?.lowercase()
                    proto != null && proto !in setOf("freedom", "blackhole", "dns", "loopback")
                }

                val entryBeans = mutableListOf<Pair<String, AbstractBean>>()
                proxyOutbounds.forEach { outbound ->
                    val ob = outbound.asJsonObject ?: return@forEach
                    val tag = ob.getString("tag")?.trim().orEmpty()
                    val parsed = parseV2RayOutbound(ob)
                    parsed.forEach { bean ->
                        bean.initializeDefaultValues()
                        bean.name = when {
                            prefix.isEmpty() && tag.isNotEmpty() -> tag
                            prefix.isNotEmpty() && proxyOutbounds.size == 1 -> prefix
                            prefix.isNotEmpty() && tag.isNotEmpty() -> "$prefix $tag"
                            else -> bean.displayName()
                        }
                        bean.profileRoutingRules = profileRoutingRules
                        entryBeans.add(tag to bean)
                        beans.add(bean)
                    }
                }

                val balancers = obj.getObject("routing")?.getArray("balancers") ?: return@forEach
                protectBalancerEntryBeans(entryBeans.map { (_, bean) -> bean })
                val multipleBalancers = balancers.size >= 2
                balancers.forEach { b ->
                    val selector = b.getStringArray("selector") ?: return@forEach
                    if (selector.isEmpty()) return@forEach
                    val strategy = b.getObject("strategy")?.getString("type") ?: "random"
                    val memberBeans = ArrayList<AbstractBean>()
                    for ((tag, bean) in entryBeans) {
                        if (selector.any { p -> tag.startsWith(p) } && memberBeans.none { it === bean }) {
                            memberBeans.add(bean)
                        }
                    }
                    if (memberBeans.size >= 2) {
                        val tagName = b.getString("tag")?.trim().orEmpty()
                        val shortTag = tagName
                            .removeSuffix("_Balancer")
                            .removeSuffix("Balancer")
                            .takeIf { it.isNotEmpty() } ?: tagName.ifEmpty { "Balancer" }
                        val balancerName = when {
                            prefix.isNotEmpty() && multipleBalancers -> "$prefix ($shortTag)"
                            prefix.isNotEmpty() -> prefix
                            else -> tagName.ifEmpty { "Balancer" }
                        }
                        addBalancerSpec(BalancerSpec(
                            name = balancerName,
                            memberBeans = memberBeans,
                            allEntryBeans = entryBeans.map { (_, bean) -> bean },
                            strategy = strategy,
                            routingRules = profileRoutingRules,
                        ))
                    }
                }
            }
            return beans
        }
        if (!jsonElement.isJsonObject) {
            return listOf()
        }
        val jsonObject = jsonElement.asJsonObject
        val beans = ArrayList<AbstractBean>()
        when {
            jsonObject.contains("protocol", ignoreCase = true) -> {
                // V2Ray JSONv4 outbound or V2Ray JSONv5 outbound
                return parseV2Ray5Outbound(jsonObject).takeIf { it.isNotEmpty() }
                    ?: parseV2RayOutbound(jsonObject)
            }
            jsonObject.contains("proxies", ignoreCase = true) -> {
                // Clash YAML
                return listOf()
            }
            jsonObject.getInt("version") != null && jsonObject.contains("servers") -> {
                // SIP008
                val element = parseJson(text)
                if (!element.isJsonObject) {
                    return listOf()
                }
                element.asJsonObject.getArray("servers")?.forEach { server ->
                    parseShadowsocksConfig(server)?.let {
                        beans.add(it)
                    }
                }
                return beans
            }
            jsonObject.contains("type") -> {
                // sing-box outbound/endpoint
                return parseSingBoxEndpoint(jsonObject).takeIf { it.isNotEmpty() }
                    ?: parseSingBoxOutbound(jsonObject)
            }
            else -> {
                val outbounds = jsonObject.getArray("outbounds", ignoreCase = true)
                val endpoints = jsonObject.getArray("endpoints", ignoreCase = true)
                val isV2Ray = !outbounds.isNullOrEmpty() && outbounds[0].contains("protocol", ignoreCase = true)
                if (isV2Ray) {
                    // V2Ray JSONv4 or V2Ray JSONv5
                    val profileRoutingRules = extractRoutingRules(jsonObject)
                    outbounds.forEach {
                        beans.addAll((parseV2Ray5Outbound(it).takeIf { it.isNotEmpty() }
                            ?: parseV2RayOutbound(it)).onEach { bean ->
                            bean.profileRoutingRules = profileRoutingRules
                        })
                    }
                    return beans
                }
                val isSingBox = !endpoints.isNullOrEmpty() || (!outbounds.isNullOrEmpty() && outbounds[0].contains("type"))
                if (isSingBox) {
                    // sing-box
                    outbounds?.forEach {
                        beans.addAll(parseSingBoxOutbound(it))
                    }
                    endpoints?.forEach {
                        beans.addAll(parseSingBoxEndpoint(it))
                    }
                    return beans
                }
                return listOf()
            }
        }
    }

    private fun extractRoutingRules(jsonObject: JsonObject): String {
        val rawRules = jsonObject.getObject("routing", ignoreCase = true)
            ?.getJsonArray("rules", ignoreCase = true)
            ?: return ""
        val rules = JsonArray()
        rawRules.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val rawRule = element.asJsonObject
            val type = rawRule.getRouteScalar("type")?.lowercase()
            if (!type.isNullOrEmpty() && type != "field") return@forEach

            val outboundTag = rawRule.getRouteScalar("outboundTag")
                ?: rawRule.getRouteScalar("outbound")
            if (outboundTag == null && rawRule.getRouteScalar("balancerTag") == null) {
                return@forEach
            }

            val domains = rawRule.getRouteList("domain", "domains").joinToString("\n")
            val ip = rawRule.getRouteList("ip", "ips").joinToString("\n")
            val port = rawRule.getRouteScalar("port").orEmpty()
            val sourcePort = rawRule.getRouteScalar("sourcePort").orEmpty()
            val network = when (rawRule.getRouteScalar("network")?.lowercase()) {
                "tcp" -> "tcp"
                "udp" -> "udp"
                else -> ""
            }
            val source = rawRule.getRouteList("source").joinToString("\n")
            val protocol = rawRule.getRouteList("protocol").joinToString("\n")
            val attrs = rawRule.getRouteScalar("attrs").orEmpty()
            val packages = rawRule.getRouteList("process", "processes")
            if (
                domains.isEmpty() && ip.isEmpty() && port.isEmpty() && sourcePort.isEmpty() &&
                network.isEmpty() && source.isEmpty() && protocol.isEmpty() && attrs.isEmpty() &&
                packages.isEmpty()
            ) {
                return@forEach
            }

            rules.add(rawRule.deepCopy())
        }
        return rules.takeIf { it.size() > 0 }?.toString().orEmpty()
    }

    private fun JsonObject.getRouteList(vararg keys: String): List<String> {
        for (key in keys) {
            val element = getRouteElement(key) ?: continue
            val values = when {
                element.isJsonArray -> element.asJsonArray.mapNotNull { it.asRouteString() }
                else -> listOfNotNull(element.asRouteString())
            }.mapNotNull { it.trim().ifBlank { null } }
            if (values.isNotEmpty()) return values
        }
        return emptyList()
    }

    private fun JsonObject.getRouteScalar(key: String): String? {
        return getRouteElement(key)?.asRouteString()?.trim()?.ifBlank { null }
    }

    private fun JsonObject.getRouteElement(key: String): JsonElement? {
        get(key)?.takeUnless { it.isJsonNull }?.let { return it }
        for ((candidate, value) in entrySet()) {
            if (candidate.equals(key, ignoreCase = true) && !value.isJsonNull) {
                return value
            }
        }
        return null
    }

    private fun JsonElement.asRouteString(): String? {
        return when {
            isJsonNull -> null
            isJsonPrimitive -> asString
            else -> toString()
        }
    }
}

private class YAMLConstructor(
    options: LoaderOptions,
) : Constructor(options) {
    override fun constructObject(node: Node): Any? {
        when (node.tag) {
            Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.STR, Tag.SEQ, Tag.MAP, Tag.BINARY,
            Tag.TIMESTAMP, Tag.SET, Tag.OMAP, Tag.PAIRS, Tag.YAML, Tag.MERGE -> {
                return super.constructObject(node)
            }
        }
        // ignore unknown tags
        return when (node) {
            is MappingNode -> constructMapping(node)
            is SequenceNode -> constructSequence(node)
            is ScalarNode -> constructScalar(node)
            else -> null
        }
    }
}
