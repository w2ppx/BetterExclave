package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BalancerBeanSerializationTest {

    private fun roundTrip(bean: BalancerBean): BalancerBean {
        val out = ByteArrayOutputStream()
        ByteBufferOutput(out).apply { bean.serializeToBuffer(this); flush(); close() }
        return BalancerBean().apply {
            deserializeFromBuffer(ByteBufferInput(ByteArrayInputStream(out.toByteArray())))
            initializeDefaultValues()
        }
    }

    @Test
    fun roundTripsNewFields() {
        for (type in listOf(BalancerBean.TYPE_LIST, BalancerBean.TYPE_GROUP)) {
            val bean = BalancerBean().apply {
                this.type = type
                name = "Balancer"
                strategy = "fallback"
                proxies = listOf(3L, 1L, 2L)
                groupId = 7L
                fallbackProxy = 9L
                profileRoutingRules = "[]"
                profileDnsHosts = """{"a.example":["::1","127.0.0.1"]}"""
                initializeDefaultValues()
            }
            val copy = roundTrip(bean)
            assertEquals(9L, copy.fallbackProxy)
            assertEquals(bean.profileDnsHosts, copy.profileDnsHosts)
            assertEquals("[]", copy.profileRoutingRules)
            assertEquals("fallback", copy.strategy)
            if (type == BalancerBean.TYPE_LIST) {
                assertEquals(listOf(3L, 1L, 2L), copy.proxies)
                assertEquals(listOf(3L, 1L, 2L, 9L), copy.listMemberIds())
            } else {
                assertEquals(7L, copy.groupId)
            }
        }
    }

    @Test
    fun readsVersion5BlobWithExtraVersion3() {
        // the layout written before fallbackProxy and profileDnsHosts existed
        val out = ByteArrayOutputStream()
        ByteBufferOutput(out).apply {
            writeInt(5)
            writeInt(BalancerBean.TYPE_LIST)
            writeString("leastPing")
            writeInt(2); writeLong(11L); writeLong(12L)
            writeString("https://probe")
            writeInt(60)
            writeInt(3) // extra version
            writeString("Old")
            writeInt(0) // ExtraType.NONE
            writeString("[{\"x\":1}]")
            flush(); close()
        }
        val bean = BalancerBean().apply {
            deserializeFromBuffer(ByteBufferInput(ByteArrayInputStream(out.toByteArray())))
            initializeDefaultValues()
        }
        assertEquals("Old", bean.name)
        assertEquals("leastPing", bean.strategy)
        assertEquals(listOf(11L, 12L), bean.proxies)
        assertEquals("https://probe", bean.probeUrl)
        assertEquals(60, bean.probeInterval)
        assertEquals("[{\"x\":1}]", bean.profileRoutingRules)
        assertEquals(0L, bean.fallbackProxy)
        assertEquals("", bean.profileDnsHosts)
        assertEquals(listOf(11L, 12L), bean.listMemberIds())
    }

    @Test
    fun proxyBeanKeepsDnsHosts() {
        val bean = SOCKSBean().apply {
            serverAddress = "fi.mealkitty.food"
            serverPort = 1080
            profileDnsHosts = """{"fi.mealkitty.food":["2a12::1","2.27.0.1"]}"""
            initializeDefaultValues()
        }
        val out = ByteArrayOutputStream()
        ByteBufferOutput(out).apply { bean.serializeToBuffer(this); flush(); close() }
        val copy = SOCKSBean().apply {
            deserializeFromBuffer(ByteBufferInput(ByteArrayInputStream(out.toByteArray())))
            initializeDefaultValues()
        }
        assertEquals(bean.profileDnsHosts, copy.profileDnsHosts)
        assertEquals("fi.mealkitty.food", copy.serverAddress)
    }
}
