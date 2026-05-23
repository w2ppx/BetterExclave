/******************************************************************************
 *                                                                            *
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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.group

import androidx.core.net.toUri
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.ExtraType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocksConfig
import io.nekohasekai.sagernet.ktx.*
import libsagernetcore.Libsagernetcore

object SIP008Updater : GroupUpdater() {

    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        val sip008Response: JsonObject
        try {
            if (link.startsWith("content://", ignoreCase = true)) {
                val contentText = app.contentResolver.openInputStream(link.toUri())
                    ?.bufferedReader()
                    ?.readText()

                sip008Response = contentText?.let { parseJson(contentText).asJsonObject }
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
                sip008Response = parseJson(body).asJsonObject
            }
        } catch (_: Exception) {
            error("invalid response")
        }


        subscription.bytesUsed = sip008Response.getLong("bytes_used") ?: -1
        subscription.bytesRemaining = sip008Response.getLong("bytes_remaining") ?: -1
        subscription.applyDefaultValues()

        val servers = sip008Response.getArray("servers")

        var profiles = mutableListOf<AbstractBean>()

        if (servers != null) {
            for (profile in servers) {
                val bean = parseShadowsocksConfig(profile) ?: continue
                appendExtraInfo(profile, bean)
                profiles.add(bean)
            }
        }

        profiles.forEach { it.applyDefaultValues() }

        if (subscription.nameFilter.isNotEmpty()) {
            val pattern = Regex(subscription.nameFilter)
            profiles = profiles.filter { !pattern.containsMatchIn(it.name) }.toMutableList()
        }
        if (subscription.nameFilter1.isNotEmpty()) {
            val pattern = Regex(subscription.nameFilter1)
            profiles = profiles.filter { pattern.containsMatchIn(it.name) }.toMutableList()
        }

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            data class DedupEntry(
                val bean: AbstractBean,
                val name: String,
                val index: Int,
                var reported: Boolean = false,
            )

            val uniqueProfiles = LinkedHashMap<AbstractBean, DedupEntry>()
            for (proxy in profiles) {
                val existing = uniqueProfiles[proxy]
                if (existing != null) {
                    if (!existing.reported) {
                        val name = existing.name.replace(" (${existing.index})", "")
                        if (name.isNotEmpty()) {
                            duplicate.add("$name (${existing.index})")
                            existing.reported = true
                        }
                    }
                    duplicate.add(proxy.displayName() + " (${existing.index})")
                } else {
                    uniqueProfiles[proxy] = DedupEntry(proxy, proxy.displayName(), uniqueProfiles.size)
                }
            }
            profiles = uniqueProfiles.values.map { it.bean }.toMutableList()
        }

        val profileMap = profiles.associateBy { it.profileId }
        val toDelete = ArrayList<ProxyEntity>()
        val toReplace = exists.mapNotNull { entity ->
            val profileId = entity.requireBean().profileId
            if (profileMap.contains(profileId)) profileId to entity else let {
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
        for ((profileId, bean) in profileMap.entries) {
            val name = bean.displayName()
            if (toReplace.contains(profileId)) {
                val entity = toReplace[profileId]!!
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
            subscription.lastUpdated = System.currentTimeMillis() / 1000
            SagerDatabase.groupDao.updateGroup(proxyGroup)
        }
        finishUpdate(proxyGroup)

        if (byUser && userInterface != null) {
            userInterface.onUpdateSuccess(proxyGroup, changed, added, updated, deleted, duplicate)
        }
    }

    fun appendExtraInfo(profile: JsonObject, bean: AbstractBean) {
        bean.extraType = ExtraType.SIP008
        bean.profileId = profile.getString("id")
    }

}
