package tv.wander.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** The TV's IPv4 address on the home network (Wi-Fi or Ethernet), or null. */
object LanAddress {
    /**
     * Scans the network interfaces and returns the address a phone on the same Wi-Fi should use.
     * Skips loopback, down, virtual, point-to-point (VPN) and link-local addresses; prefers wlan and
     * eth interfaces and private (site-local) ranges. Cheap enough for the main thread (no I/O).
     */
    fun ipv4(): String? = try {
        val found = ArrayList<Candidate>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        if (interfaces != null) {
            for (nif in interfaces) {
                val usable = try {
                    nif.isUp && !nif.isLoopback && !nif.isVirtual && !nif.isPointToPoint
                } catch (e: Exception) {
                    false
                }
                if (!usable) continue
                val name = nif.name.orEmpty().lowercase()
                if (SKIPPED_PREFIXES.any { name.startsWith(it) }) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) continue
                    val host = addr.hostAddress ?: continue
                    found.add(Candidate(host, rank(name, addr.isSiteLocalAddress)))
                }
            }
        }
        pick(found)
    } catch (t: Throwable) {
        null
    }

    internal class Candidate(val host: String, val rank: Int)

    /** Lower is better: wlan/eth first, then other names; private ranges before public ones. */
    internal fun rank(interfaceName: String, siteLocal: Boolean): Int {
        val nameRank = when {
            interfaceName.startsWith("wlan") || interfaceName.startsWith("eth") -> 0
            interfaceName.startsWith("en") || interfaceName.startsWith("wl") -> 1
            else -> 2
        }
        return nameRank * 2 + if (siteLocal) 0 else 1
    }

    internal fun pick(candidates: List<Candidate>): String? = candidates.minByOrNull { it.rank }?.host

    /** Interfaces a phone on the home network can never reach the TV through. */
    private val SKIPPED_PREFIXES = listOf("tun", "ppp", "rmnet", "dummy", "docker", "veth", "virbr", "vmnet", "vboxnet", "p2p", "ipsec", "utun")
}
