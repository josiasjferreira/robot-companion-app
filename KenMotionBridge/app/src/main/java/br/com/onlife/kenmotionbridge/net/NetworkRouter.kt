package br.com.onlife.kenmotionbridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

/**
 * Roteamento multi-rede (dual-homing) do tablet do robô — **dirigido por dados**.
 *
 * O tablet pode ter VÁRIAS redes ao mesmo tempo (Wi-Fi do hotspot, USB/tethering do celular,
 * Ethernet do chassi…). Identificar a interface por TRANSPORT_* é frágil: tethering USB às vezes
 * aparece como Ethernet, o chassi pode ou não ser Ethernet, etc. Então:
 *
 *  - a rede do **CHASSI** é achada pela SUB-REDE (o IP do chassi, ex.: 192.168.99.x);
 *  - a rede de **INTERNET** é achada pela capacidade real (NET_CAPABILITY_INTERNET, validada).
 *
 * Como o RobotSDK abre o próprio socket (porta 1445) fora do nosso controle, amarramos o
 * **PROCESSO** à rede do chassi ([ConnectivityManager.bindProcessToNetwork]); o socket do MQTT é
 * amarrado à rede de internet pelo `MqttManager`. [describe] gera um inventário das redes para a
 * tela/Logcat — essencial para diagnosticar sem adivinhação. Requer `CHANGE_NETWORK_STATE`.
 */
class NetworkRouter(context: Context) {
    companion object { private const val TAG = "NetworkRouter" }

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** Rede com internet (validada de preferência). Capture ANTES de [bindProcess]. */
    fun findInternetNetwork(preferCellular: Boolean, avoid: Network? = null): Network? {
        val cm = cm ?: return null
        val withInternet = cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        if (withInternet.isEmpty()) return null
        return withInternet.maxByOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            var s = 0
            if (c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) s += 4
            if (avoid != null && n != avoid) s += 1   // prefere uma rede DIFERENTE da do chassi
            if (preferCellular && c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) s += 2
            s
        }
    }

    /** Rede cujo endereço cai na MESMA /24 do [chassisIp]. Prefere ETHERNET (cabo direto). */
    fun findChassisNetwork(chassisIp: String): Network? {
        val cm = cm ?: return null
        val prefix = chassisIp.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return null
        val matches = cm.allNetworks.filter { n ->
            cm.getLinkProperties(n)?.linkAddresses?.any {
                it.address?.hostAddress?.substringBeforeLast('.', "") == prefix
            } == true
        }
        // Cabo (Ethernet) é o caminho direto/confiável; Wi-Fi do robô pode ter isolamento de cliente.
        return matches.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        } ?: matches.firstOrNull()
    }

    /**
     * Sonda L4: tenta um TCP connect a [ip]:[port] SAINDO por [network] (ou rota padrão se null).
     * Separa "rota/porta inacessível" de "SDK não faz handshake". Bloqueante (use fora da main).
     */
    fun probeTcp(ip: String, port: Int, network: Network?, timeoutMs: Int = 1500): String {
        return try {
            val s = network?.socketFactory?.createSocket() ?: java.net.Socket()
            s.use {
                it.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
                "OK (porta aberta)"
            }
        } catch (e: java.net.SocketTimeoutException) {
            "TIMEOUT (sem resposta)"
        } catch (e: java.net.ConnectException) {
            "RECUSADO (${e.message})"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Amarra o processo a [net] (para o RobotSDK alcançar o chassi). API 23+. */
    fun bindProcess(net: Network): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "bindProcessToNetwork indisponível (API < 23)")
            return false
        }
        return runCatching { cm?.bindProcessToNetwork(net) == true }
            .onFailure { Log.w(TAG, "bindProcessToNetwork falhou: ${it.message}") }
            .getOrDefault(false)
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) runCatching { cm?.bindProcessToNetwork(null) }
    }

    /** Inventário legível das redes (transporte, internet/validada, iface, endereços). */
    fun describe(chassisIp: String): String {
        val cm = cm ?: return "ConnectivityManager indisponível"
        val nets = cm.allNetworks
        if (nets.isEmpty()) return "Nenhuma rede ativa"
        val prefix = chassisIp.substringBeforeLast('.', "")
        return buildString {
            for (n in nets) {
                val c = cm.getNetworkCapabilities(n)
                val lp = cm.getLinkProperties(n)
                val t = buildList {
                    if (c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
                    if (c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELL")
                    if (c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETH")
                    if (c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                    if (c?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("BT")
                }.joinToString("/").ifEmpty { "?" }
                val inet = if (c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) "INET" else "—"
                val valid = if (c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "OK" else "—"
                val iface = lp?.interfaceName ?: "?"
                val addrs = lp?.linkAddresses?.joinToString(",") { it.address?.hostAddress ?: "?" } ?: ""
                val isChassi = lp?.linkAddresses?.any {
                    it.address?.hostAddress?.substringBeforeLast('.', "") == prefix
                } == true
                append("• $iface [$t] $inet val=$valid${if (isChassi) " ◀CHASSI" else ""}\n  $addrs\n")
            }
        }.trimEnd()
    }
}
