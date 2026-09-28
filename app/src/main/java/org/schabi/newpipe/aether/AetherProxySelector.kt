package org.schabi.newpipe.aether

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * A [ProxySelector] that routes all non-loopback traffic through the Aether
 * tunnel listener and never proxies loopback/self traffic (avoids recursion
 * and keeps the Aether control channel direct).
 *
 * Installed as `ProxySelector.setDefault(...)` while the tunnel is running so
 * that `HttpURLConnection`-based stacks (ExoPlayer `DefaultHttpDataSource`,
 * `YoutubeHttpDataSource`, and the future giga downloader) honor it. The
 * OkHttp stack is routed separately via [DownloaderImpl.setProxy] since OkHttp
 * does not re-read the global selector after the client is built.
 */
class AetherProxySelector(
    private val proxyAddress: SocketAddress
) : ProxySelector() {

    private val proxy = Proxy(Proxy.Type.SOCKS, proxyAddress)

    override fun select(uri: URI): List<Proxy> {
        val host = uri.host
        if (host == null || isLoopback(host)) {
            return NO_PROXY
        }
        return listOf(proxy)
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        // Aether reconnects on its own; the selector stays installed while
        // the tunnel is running.
    }

    companion object {
        private val NO_PROXY = listOf(Proxy.NO_PROXY)

        private fun isLoopback(host: String): Boolean {
            if (host == "localhost") return true
            if (host.startsWith("127.") || host == "::1") return true
            if (host == "10.0.2.2" || host == "10.0.2.15") return true // emulator loopback
            return false
        }
    }
}
