package br.com.onlife.kenmotionbridge.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import br.com.onlife.kenmotionbridge.BridgeConfig
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.MqttSecurityException
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Cliente MQTT NATIVO (Paho) ligado ao MESMO broker do app web (HiveMQ Cloud).
 *
 * Importante: o navegador/Lovable usa WebSocket Secure (`wss://HOST:8884/mqtt`); o cliente
 * Paho nativo no Android deve usar **MQTT/TLS** (`ssl://HOST:8883`). Ambos são suportados aqui,
 * mas o padrão recomendado é `ssl://...:8883`.
 *
 * Em caso de falha, reporta o MOTIVO exato (auth, host inacessível, TLS handshake) via
 * [onConnectionChanged] para que a tela mostre o erro real em vez de só "DESCONECTADO".
 *
 * Split de rotas (Fase 2): se [BridgeConfig.mqttForceCellular] estiver ligado, o socket do
 * MQTT é amarrado à rede CELULAR (4G/USB), deixando o Wi-Fi livre para a rede local do robô
 * (chassi 192.168.99.x). Assim chassi e broker funcionam ao mesmo tempo.
 */
class MqttManager(
    private val context: Context,
    private val config: BridgeConfig,
    /** (conectado, mensagemDeErro?) — erro só vem preenchido quando conectado=false. */
    private val onConnectionChanged: (Boolean, String?) -> Unit,
    private val onCommand: (String) -> Unit,
) {
    companion object { private const val TAG = "MqttManager" }

    private var client: MqttAsyncClient? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

    fun connect() {
        try {
            val cid = "${config.clientId}-${(SecureRandom().nextInt(9000) + 1000)}"
            val c = MqttAsyncClient(config.mqttUri, cid, MemoryPersistence())
            client = c

            val opts = MqttConnectOptions().apply {
                // Reconexão automática + sessão limpa, conforme pedido.
                isAutomaticReconnect = true
                isCleanSession = config.cleanSession            // default true
                keepAliveInterval = config.keepAliveSec          // default 30 s
                connectionTimeout = 10                            // 10 s
                isHttpsHostnameVerificationEnabled = true
                // Credenciais aplicadas DIRETO no opts passado ao connect (não são sobrescritas depois).
                // Trim defensivo: remove espaços/quebras acidentais que quebram a autenticação.
                if (config.mqttUser.isNotEmpty()) {
                    userName = config.mqttUser.trim()
                    password = config.mqttPassword.trim().toCharArray()
                }
                // TLS habilitado para ssl:// e wss:// (SSLSocketFactory padrão do sistema).
                if (config.mqttUri.startsWith("ssl") || config.mqttUri.startsWith("wss")) {
                    val base = buildSslContext().socketFactory
                    // Split de rotas: amarra o socket TLS à rede celular, se solicitado.
                    socketFactory = if (config.mqttForceCellular) {
                        val net = acquireCellular(6000)
                        if (net != null) {
                            val host = BridgeConfig.hostFromUri(config.mqttUri)
                            val port = BridgeConfig.portFromUri(config.mqttUri)
                            Log.i(TAG, "MQTT amarrado à rede CELULAR (4G/USB) para $host:$port")
                            CellularSslSocketFactory(net, base, host, port)
                        } else {
                            Log.w(TAG, "forceCellular ligado, mas rede celular indisponível — usando rota padrão")
                            base
                        }
                    } else base
                }
            }

            // Log do usuário EXATO enviado (senha mascarada) — confira no Logcat e na tela.
            Log.i(
                TAG,
                "Conectando a ${config.mqttUri} | clientId=$cid | user='${opts.userName}' " +
                    "(len=${opts.userName?.length ?: 0}) | passLen=${opts.password?.size ?: 0}"
            )

            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "Conectado (reconnect=$reconnect) a $serverURI")
                    onConnectionChanged(true, null)
                    subscribeCmd()
                }
                override fun connectionLost(cause: Throwable?) {
                    val msg = describe(cause)
                    Log.w(TAG, "Conexão perdida: $msg")
                    onConnectionChanged(false, "conexão perdida — $msg")
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == config.topicCmd && message != null) {
                        onCommand(String(message.payload))
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            c.connect(opts, null, object : org.eclipse.paho.client.mqttv3.IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {}
                override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, ex: Throwable?) {
                    val msg = describe(ex)
                    Log.e(TAG, "Falha ao conectar: $msg", ex)
                    onConnectionChanged(false, msg)
                }
            })
        } catch (e: Throwable) {
            val msg = describe(e)
            Log.e(TAG, "Erro de MQTT: $msg", e)
            onConnectionChanged(false, msg)
        }
    }

    private fun subscribeCmd() {
        runCatching { client?.subscribe(config.topicCmd, 0) }
            .onSuccess { Log.i(TAG, "Inscrito em ${config.topicCmd}") }
            .onFailure { Log.e(TAG, "Falha ao inscrever: ${it.message}") }
    }

    fun publish(topic: String, payload: String, qos: Int = 0, retained: Boolean = false) {
        val c = client ?: return
        if (!c.isConnected) return
        runCatching {
            c.publish(topic, MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
                isRetained = retained
            })
        }.onFailure { Log.w(TAG, "Falha ao publicar em $topic: ${it.message}") }
    }

    fun isConnected(): Boolean = client?.isConnected == true

    fun disconnect() {
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
        client = null
        cellularCallback?.let { cb ->
            runCatching {
                context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        cellularCallback = null
    }

    /**
     * Solicita e aguarda (até [timeoutMs]) a rede CELULAR com internet. Mantém o callback
     * registrado para o sistema preservar o 4G/USB ativo durante a sessão (liberado em [disconnect]).
     */
    private fun acquireCellular(timeoutMs: Long): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val holder = AtomicReference<Network?>()
        val latch = CountDownLatch(1)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { holder.set(network); latch.countDown() }
        }
        return try {
            cellularCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            cellularCallback = cb
            cm.requestNetwork(req, cb)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            holder.get()
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao obter rede celular: ${t.message}"); null
        }
    }

    /**
     * SSLSocketFactory que cria o socket de transporte AMARRADO a uma [Network] específica
     * (celular) e o envelopa em TLS. O Paho usa `createSocket()` (sem args) e depois `connect()`;
     * por isso o socket base (não conectado, já vinculado à rede) é envelopado e conectado pelo Paho.
     */
    private class CellularSslSocketFactory(
        private val network: Network,
        private val delegate: SSLSocketFactory,
        private val host: String,
        private val port: Int,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(): Socket {
            val base = network.socketFactory.createSocket()   // não conectado, vinculado ao celular
            return delegate.createSocket(base, host, port, true)
        }
        override fun createSocket(h: String?, p: Int): Socket =
            delegate.createSocket(network.socketFactory.createSocket(h, p), host, port, true)
        override fun createSocket(h: String?, p: Int, lh: InetAddress?, lp: Int): Socket =
            delegate.createSocket(network.socketFactory.createSocket(h, p, lh, lp), host, port, true)
        override fun createSocket(a: InetAddress?, p: Int): Socket =
            delegate.createSocket(network.socketFactory.createSocket(a, p), host, port, true)
        override fun createSocket(a: InetAddress?, p: Int, lh: InetAddress?, lp: Int): Socket =
            delegate.createSocket(network.socketFactory.createSocket(a, p, lh, lp), host, port, true)
        override fun createSocket(s: Socket?, h: String?, p: Int, autoClose: Boolean): Socket =
            delegate.createSocket(s, h, p, autoClose)
    }

    /**
     * Traduz a exceção do Paho num motivo legível para a tela:
     * autenticação, host inacessível ou falha de TLS.
     */
    private fun describe(t: Throwable?): String {
        val root = rootCause(t)
        // 1) Erros de autenticação/autorização do MQTT (códigos do CONNACK).
        val mqttEx = (t as? MqttException) ?: (root as? MqttException)
        if (t is MqttSecurityException || mqttEx?.reasonCode?.toInt() in AUTH_CODES) {
            return "AUTENTICAÇÃO falhou — usuário/senha do HiveMQ incorretos ou sem permissão"
        }
        return when {
            root is SSLHandshakeException ->
                "TLS handshake falhou — certificado/relógio do dispositivo ou porta errada (use ssl://…:8883). ${root.message ?: ""}".trim()
            root is SSLException ->
                "Erro TLS — ${root.message ?: "falha no canal seguro"}"
            root is UnknownHostException ->
                "HOST inacessível — não foi possível resolver o endereço (verifique o host e a internet)"
            root is ConnectException ->
                "HOST inacessível — conexão recusada/sem rota (verifique host:porta e firewall)"
            root is SocketTimeoutException ->
                "TIMEOUT — broker não respondeu em 10 s (host/porta/internet)"
            mqttEx != null ->
                "MQTT erro ${mqttEx.reasonCode}: ${mqttEx.message ?: root?.message ?: ""}".trim()
            else ->
                root?.message ?: t?.message ?: "erro desconhecido"
        }
    }

    private fun rootCause(t: Throwable?): Throwable? {
        var cur = t
        while (cur?.cause != null && cur.cause !== cur) cur = cur.cause
        return cur
    }

    /**
     * SSLContext para o HiveMQ Cloud. Por padrão usa os trust managers do sistema
     * (CA pública do HiveMQ). Se `tlsInsecure=true`, aceita qualquer certificado
     * (APENAS para diagnóstico em rede fechada — não usar em produção).
     */
    private fun buildSslContext(): SSLContext {
        return if (config.tlsInsecure) {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        } else {
            SSLContext.getInstance("TLS").apply { init(null, null, null) }
        }
    }
}

/** Códigos de CONNACK do Paho que indicam falha de credenciais. */
private val AUTH_CODES = setOf(
    MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),  // 4 — bad user/password
    MqttException.REASON_CODE_NOT_AUTHORIZED.toInt(),         // 5 — not authorized
)
