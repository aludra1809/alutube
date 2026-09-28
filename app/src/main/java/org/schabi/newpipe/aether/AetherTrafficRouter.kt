package org.schabi.newpipe.aether

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import org.schabi.newpipe.DownloaderImpl

/**
 * Applies/removes Aether traffic routing for the whole process:
 *
 *  - **OkHttp stack** (`DownloaderImpl`, used by the extractor for all
 *    requests and by Coil for images): the client is rebuilt with a SOCKS5
 *    proxy so every extractor request goes through the tunnel. OkHttp does
 *    not re-read the global selector after the client is built, so this is
 *    the only way to route that stack.
 *
 *  - **HttpURLConnection stack** (ExoPlayer `DefaultHttpDataSource`,
 *    `YoutubeHttpDataSource`, and the future giga downloader): a global
 *    [ProxySelector] routes every `URL.openConnection()` call through the
 *    tunnel while it is installed. Loopback hosts are never proxied.
 *
 *  - DNS: with a SOCKS5 proxy, the Aether listener resolves hostnames
 *    through the tunnel (the Aether socks server performs remote DNS), so
 *    both stacks get tunnel DNS without an app-side resolver.
 */
interface AetherTrafficRouter {
    /** Route process traffic through the Aether listener at `host:port`. */
    fun routeThrough(host: String, port: Int)

    /** Remove routing; restore direct connections. */
    fun routeDirect()
}

class DefaultAetherTrafficRouter : AetherTrafficRouter {
    override fun routeThrough(host: String, port: Int) {
        val address = InetSocketAddress(host, port)
        val proxy = Proxy(Proxy.Type.SOCKS, address)

        // OkHttp stack: DownloaderImpl is a process singleton created at app
        // startup; when it exists, rebuild its client with the proxy.
        DownloaderImpl.getInstance()?.setProxy(proxy)

        // HttpURLConnection stack: global selector is consulted by
        // URL.openConnection() at connection time.
        ProxySelector.setDefault(AetherProxySelector(address))
    }

    override fun routeDirect() {
        DownloaderImpl.getInstance()?.setProxy(null)
        ProxySelector.setDefault(null)
    }
}
