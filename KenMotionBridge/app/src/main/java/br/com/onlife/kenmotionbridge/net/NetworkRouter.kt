package br.com.onlife.kenmotionbridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Roteamento multi-rede (dual-homing) do tablet do robô.
 *
 * Topologia real do KEN:
 *  - CHASSI numa LAN **Ethernet** (ex.: 192.168.99.x) **sem internet**;
 *  - INTERNET (MQTT) por outra rede (Wi-Fi/4G/USB).
 *
 * Problema: o Android elege como rede PADRÃO a que tem internet e manda todo socket
 * não-amarrado por ela — cuja tabela de rotas NÃO contém a sub-rede do chassi. O RobotSDK
 * abre o PRÓPRIO socket TCP (porta 1445), fora do nosso controle, então não dá para amarrá-lo
 * individualmente. A solução é amarrar o **PROCESSO** à rede Ethernet
 * ([ConnectivityManager.bindProcessToNetwork]); o MQTT, por sua vez, é amarrado explicitamente
 * à rede de internet pelo próprio cliente (ver `MqttManager`), independente do default do processo.
 *
 * Requer `CHANGE_NETWORK_STATE` (já declarada). `bindProcessToNetwork` é API 23+; em versões
 * anteriores não há split possível e o dual-homing fica inativo (sem regressão).
 */
class NetworkRouter(context: Context) {
    companion object { private const val TAG = "NetworkRouter" }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val ethernet = AtomicReference<Network?>()
    private var ethCallback: ConnectivityManager.NetworkCallback? = null
    private val ethLatch = AtomicReference(CountDownLatch(1))

    /**
     * Pede a rede ETHERNET e amarra o PROCESSO a ela (para o RobotSDK alcançar o chassi).
     * Aguarda até [awaitMs] a Ethernet ficar disponível. Retorna true se conseguiu amarrar.
     */
    fun bindChassisToEthernet(awaitMs: Long): Boolean {
        val cm = cm ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "bindProcessToNetwork indisponível (API < 23) — dual-homing inativo")
            return false
        }
        if (ethCallback == null) {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    ethernet.set(network)
                    ethLatch.get().countDown()
                    applyBinding(network)
                    Log.i(TAG, "Ethernet disponível ($network) — processo amarrado para o chassi")
                }
                override fun onLost(network: Network) {
                    if (ethernet.compareAndSet(network, null)) {
                        ethLatch.set(CountDownLatch(1))
                        clearBinding()
                        Log.w(TAG, "Ethernet perdida — binding do processo removido")
                    }
                }
            }
            val ok = runCatching { cm.requestNetwork(req, cb) }.isSuccess
            if (!ok) { Log.w(TAG, "Falha ao requisitar rede Ethernet"); return false }
            ethCallback = cb
        }
        ethernet.get()?.let { applyBinding(it); return true }
        runCatching { ethLatch.get().await(awaitMs, TimeUnit.MILLISECONDS) }
        val net = ethernet.get()
        if (net != null) applyBinding(net) else Log.w(TAG, "Ethernet não apareceu em ${awaitMs}ms")
        return net != null
    }

    private fun applyBinding(net: Network) {
        runCatching { cm?.bindProcessToNetwork(net) }
            .onFailure { Log.w(TAG, "bindProcessToNetwork falhou: ${it.message}") }
    }

    private fun clearBinding() {
        runCatching { cm?.bindProcessToNetwork(null) }
    }

    fun release() {
        ethCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        ethCallback = null
        ethernet.set(null)
        clearBinding()
    }
}
