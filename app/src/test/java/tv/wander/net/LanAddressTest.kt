package tv.wander.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
    @Test
    fun prefersWifiAndEthernetOnPrivateRanges() {
        val picked = LanAddress.pick(
            listOf(
                LanAddress.Candidate("100.64.3.9", LanAddress.rank("rndis0", false)),
                LanAddress.Candidate("192.168.49.1", LanAddress.rank("ap0", true)),
                LanAddress.Candidate("192.168.1.23", LanAddress.rank("wlan0", true)),
                LanAddress.Candidate("10.0.0.5", LanAddress.rank("eth0", true)),
            ),
        )
        assertEquals("192.168.1.23", picked)
        assertTrue(LanAddress.rank("eth0", true) < LanAddress.rank("eth0", false))
        assertTrue(LanAddress.rank("wlan0", false) < LanAddress.rank("ap0", true))
        assertNull(LanAddress.pick(emptyList()))
    }

    @Test
    fun scanReturnsAUsableIpv4OrNothing() {
        val ip = LanAddress.ipv4() ?: return // a build machine may have no network at all
        val parts = ip.split('.')
        assertEquals(ip, 4, parts.size)
        assertTrue(ip, parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true })
        assertFalse("not loopback: $ip", ip.startsWith("127."))
        assertFalse("not link-local: $ip", ip.startsWith("169.254."))
    }
}
