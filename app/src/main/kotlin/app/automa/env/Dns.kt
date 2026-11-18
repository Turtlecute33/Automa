package app.automa.env

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.InetAddress

/**
 * `/etc/resolv.conf` for the guest (research F-5). musl sends plain DNS to port 53, and the app cannot
 * relay it, so the guest uses the same resolver as Android: the strict Private DNS server, else the DNS servers of
 * the active network (the VPN when one is up). Runs on a background thread (it can resolve a host name).
 */
object Dns {
    fun write(context: Context, file: File) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val link = cm.getLinkProperties(cm.activeNetwork)
        // Strict mode has a host name. The system resolver (Private DNS itself) gives its addresses.
        val host = link?.privateDnsServerName?.takeIf { link.isPrivateDnsActive }
        val private = host?.let { runCatching { InetAddress.getAllByName(it).toList() }.getOrNull() }.orEmpty()
        val text = conf(private, network = link?.dnsServers.orEmpty())
        if (file.exists() && runCatching { file.readText() }.getOrNull() == text) return
        file.parentFile?.mkdirs()
        // The guest can make it a symbolic link; replace the link, do not write through it.
        file.delete()
        file.writeText(text)
    }

    /**
     * musl reads at most three servers and asks all of them at once, so only one group goes in: the strict Private
     * DNS servers, else the network servers (automatic Private DNS uses the network servers too).
     */
    fun conf(private: List<InetAddress>, network: List<InetAddress>): String {
        val servers = private.ifEmpty { network }
        return "# Written by Automa before each start.\n" +
            servers.take(3).joinToString("") { "nameserver ${it.hostAddress}\n" }
    }
}
