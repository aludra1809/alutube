package org.schabi.newpipe.aether

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * App-level holder for the single [AetherNetworkController] instance.
 *
 * The controller is created lazily with an application context so it survives
 * across activities; its traffic routing (OkHttp proxy + global
 * ProxySelector) is applied only while the tunnel is RUNNING.
 */
object AetherConnectionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var controller: AetherNetworkController? = null

    @Synchronized
    fun get(context: Context): AetherNetworkController {
        controller?.let { return it }
        val appContext = context.applicationContext
        return AetherNetworkController(appContext, scope = scope).also {
            controller = it
        }
    }

    /**
     * Release the controller (used on app shutdown). Routing is reset to
     * direct and native resources are freed.
     */
    @Synchronized
    fun shutdown() {
        controller?.stop()
        controller = null
        scope.cancel()
    }
}
