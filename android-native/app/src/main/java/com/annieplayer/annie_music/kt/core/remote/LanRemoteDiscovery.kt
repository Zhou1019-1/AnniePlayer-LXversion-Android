package com.annieplayer.annie_music.kt.core.remote

import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class RemoteSubnet(val prefix: String, val selfAddress: String)

fun subnetHosts(prefix: String): List<String> = (1..254).map { "$prefix.$it" }

fun remoteServerSignature(statusCode: Int, body: String): Boolean =
    statusCode == 401 && body.contains("unauthorized")

fun localSubnet(): RemoteSubnet? {
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
    for (iface in interfaces) {
        if (!runCatching { iface.isUp }.getOrDefault(false)) continue
        if (runCatching { iface.isLoopback }.getOrDefault(true)) continue
        val addresses = runCatching { iface.interfaceAddresses }.getOrNull().orEmpty()
        for (entry in addresses) {
            val ip = entry.address
            if (ip !is Inet4Address) continue
            val host = ip.hostAddress ?: continue
            if (host.startsWith("169.254.") || host.startsWith("198.18.")) continue
            val parts = host.split('.')
            if (parts.size != 4) continue
            return RemoteSubnet("${parts[0]}.${parts[1]}.${parts[2]}", host)
        }
    }
    return null
}

object LanRemoteDiscovery {

    private const val ProbeTimeoutMs = 300
    private const val ConfirmTimeoutMs = 900
    private const val Concurrency = 48

    private val probeHttp: OkHttpClient by lazy {
        globalOkHttp.newBuilder()
            .connectTimeout(ConfirmTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(ConfirmTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun discover(
        port: Int = RemoteDefaultPort,
        recent: List<String> = emptyList(),
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): String? = withContext(Dispatchers.IO) {
        val knownHosts = recent.map { it.substringBefore(':').trim() }.filter { it.isNotEmpty() }
        for (host in knownHosts) {
            if (reachable(host, port) && confirm(host, port)) {
                onProgress(1, 1)
                return@withContext "$host:$port"
            }
        }
        val subnet = localSubnet() ?: return@withContext null
        val hosts = subnetHosts(subnet.prefix).filter { it != subnet.selfAddress }
        val total = hosts.size
        val hit = AtomicReference<String?>(null)
        val done = AtomicInteger(0)
        coroutineScope {
            val gate = Semaphore(Concurrency)
            hosts.map { host ->
                async {
                    gate.withPermit {
                        if (hit.get() == null && reachable(host, port) && confirm(host, port)) {
                            hit.compareAndSet(null, "$host:$port")
                        }
                        onProgress(done.incrementAndGet(), total)
                    }
                }
            }.awaitAll()
        }
        hit.get()
    }

    private fun reachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), ProbeTimeoutMs) }
    }.isSuccess

    private fun confirm(host: String, port: Int): Boolean = runCatching {
        val request = Request.Builder()
            .url("http://$host:$port/api/state?token=probe")
            .get()
            .build()
        probeHttp.newCall(request).execute().use { response ->
            remoteServerSignature(response.code, response.body?.string().orEmpty())
        }
    }.getOrDefault(false)
}
