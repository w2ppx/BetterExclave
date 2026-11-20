package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.DnsObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrayProfileExtrasTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun parsesDurations() {
        assertEquals(30, parseXrayDurationSeconds("30s"))
        assertEquals(300, parseXrayDurationSeconds("5m"))
        assertEquals(90, parseXrayDurationSeconds("1m30s"))
        assertEquals(5400, parseXrayDurationSeconds("1.5h"))
        assertEquals(1, parseXrayDurationSeconds("500ms"))
        assertEquals(45, parseXrayDurationSeconds(" 45 "))
        assertNull(parseXrayDurationSeconds(""))
        assertNull(parseXrayDurationSeconds("abc"))
        assertNull(parseXrayDurationSeconds("10x"))
        assertNull(parseXrayDurationSeconds("s10"))
    }

    @Test
    fun probeSettingsFromObservatory() {
        val (url, interval) = extractXrayProbeSettings(obj(
            """{"observatory":{"probeURL":"https://a/gen_204","probeInterval":"1m"},
               "burstObservatory":{"pingConfig":{"destination":"https://b","interval":"5m"}}}"""
        ))
        assertEquals("https://a/gen_204", url)
        assertEquals(60, interval)
    }

    @Test
    fun probeSettingsFromBurstObservatory() {
        val (url, interval) = extractXrayProbeSettings(obj(
            """{"burstObservatory":{"subjectSelector":["p"],
               "pingConfig":{"destination":"https://cp.cloudflare.com/","interval":"2m30s","sampling":3}}}"""
        ))
        assertEquals("https://cp.cloudflare.com/", url)
        assertEquals(150, interval)
        assertEquals(null to null, extractXrayProbeSettings(obj("""{"outbounds":[]}""")))
    }

    @Test
    fun extractsDnsHostsStringsAndLists() {
        val hosts = extractXrayDnsHosts(obj(
            """{"dns":{"hosts":{
                "fi.mealkitty.food":["2a12:5940::1","2.27.0.1"],
                "one.example":"1.1.1.1",
                "dup.example":["9.9.9.9","9.9.9.9"],
                "empty.example":[],
                "bad.example":{"x":1}
            },"servers":["1.1.1.1"]}}"""
        ))
        assertEquals(
            obj("""{"fi.mealkitty.food":["2a12:5940::1","2.27.0.1"],"one.example":"1.1.1.1","dup.example":"9.9.9.9"}"""),
            obj(hosts),
        )
        assertEquals("", extractXrayDnsHosts(obj("""{"dns":{"servers":["1.1.1.1"]}}""")))
        assertEquals("", extractXrayDnsHosts(obj("""{}""")))
    }

    @Test
    fun mergeKeepsExistingEntries() {
        val hosts = mutableMapOf<String, DnsObject.StringOrListObject>(
            "one.example" to DnsObject.StringOrListObject().apply { valueX = "10.0.0.1" },
        )
        mergeProfileDnsHosts(hosts, """{"one.example":"1.1.1.1","fi.mealkitty.food":["2a12::1","2.27.0.1"]}""")
        mergeProfileDnsHosts(hosts, """{"fi.mealkitty.food":"8.8.8.8","two.example":"2.2.2.2"}""")
        mergeProfileDnsHosts(hosts, "not json")
        mergeProfileDnsHosts(hosts, "")

        assertEquals("10.0.0.1", hosts["one.example"]!!.valueX)
        assertEquals(listOf("2a12::1", "2.27.0.1"), hosts["fi.mealkitty.food"]!!.valueY)
        assertNull(hosts["fi.mealkitty.food"]!!.valueX)
        assertEquals("2.2.2.2", hosts["two.example"]!!.valueX)
        assertEquals(3, hosts.size)
    }
}
