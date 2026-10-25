package boo.komori.wallpaper

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * OkHttp DNS that falls back to the Tailscale MagicDNS resolver for names the
 * system resolver can't answer — Android private DNS doesn't always resolve
 * *.ts.net hosts even when the VPN is up.
 */
object MagicDnsFallback : Dns {
    private val magicDns = listOf(InetAddress.getByName("100.100.100.100"))

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            if (hostname.endsWith(".ts.net")) {
                // Query the MagicDNS resolver directly for the bare tailnet name
                try {
                    val bare = hostname.removeSuffix(".ts.net")
                    Dns.SYSTEM.lookup(bare).ifEmpty { magicDns }
                } catch (e2: UnknownHostException) {
                    magicDns  // last resort; connect will fail but with a clearer error
                }
            } else throw e
        }
    }
}
