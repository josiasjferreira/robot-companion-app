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
import org.eclipse.paho.client.mqttv3.MqttCallback
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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
 *
 * Reconexão robusta (Fase 11): em vez do auto-reconnect do Paho — que reaproveita a mesma
 * `socketFactory` com uma instância de [Network] capturada UMA vez — fazemos a reconexão por
 * conta própria. A cada tentativa o cliente e a `socketFactory` são RECONSTRUÍDOS, re-amarrando
 * o socket à rede celular ATUAL. Um [ConnectivityManager.NetworkCallback] persistente observa a
 * celular/USB e força reconexão quando a instância de rede troca (causa raiz do erro
 * "Software caused connection abort": socket preso a uma rede que deixou de existir).
 */
class MqttManager(
    private val context: Context,
    private val config: BridgeConfig,
    /** (conectado, mensagemDeErro?) — erro só vem preenchido quando conectado=false. */
    private val onConnectionChanged: (Boolean, String?) -> Unit,
    private val onCommand: (String) -> Unit,
) {
    companion object {
        private const val TAG = "MqttManager"

        /**
         * Backoff exponencial com teto para as tentativas de reconexão.
         * tentativa 0 -> 2s, 1 -> 4s, 2 -> 8s, 3 -> 16s, 4+ -> 30s (teto).
         * Função pura (sem dependência de Android) para permitir teste unitário.
         */
        fun backoffDelayMs(attempt: Int): Long {
            val shift = attempt.coerceIn(0, 4)
            return (2_000L shl shift).coerceAtMost(30_000L)
        }
    }

    private var client: MqttAsyncClient? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

    /** Rede celular/USB ATUAL (atualizada pelo callback). null = indisponível no momento. */
    private val cellularNet = AtomicReference<Network?>()

    /** Verdadeiro entre [connect] e [disconnect]; controla se devemos reagendar reconexões. */
    @Volatile private var wantConnected = false

    /** Contador do backoff; zerado a cada conexão bem-sucedida. */
    private var attempt = 0

    private val reconnectExec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mqtt-reconnect").apply { isDaemon = true }
        }
    private var pending: ScheduledFuture<*>? = null

    fun connect() {
        wantConnected = true
        if (config.mqttForceCellular) registerCellularCallback()
        doConnect()
    }

    /** (Re)cria o cliente e tenta conectar. Sincronizado para serializar com o agendador. */
    @Synchronized
    private fun doConnect() {
        if (!wantConnected) return
        closeClientQuietly()
        try {
            val cid = "${config.clientId}-${(SecureRandom().nextInt(9000) + 1000)}"
            val c = MqttAsyncClient(config.mqttUri, cid, MemoryPersistence())
            client = c

            val opts = MqttConnectOptions().apply {
                // Reconexão é nossa (re-amarra a rede a cada tentativa); a do Paho fica DESLIGADA.
                isAutomaticReconnect = false
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
                    // Split de rotas: amarra o socket TLS à rede celular ATUAL, se solicitado.
                    socketFactory = if (config.mqttForceCellular) {
                        val net = awaitCellular(6000)
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

            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    val msg = describe(cause)
                    Log.w(TAG, "Conexão perdida: $msg")
                    onConnectionChanged(false, "conexão perdida — $msg")
                    scheduleReconnect()
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == config.topicCmd && message != null) {
                        onCommand(String(message.payload))
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            c.connect(opts, null, object : org.eclipse.paho.client.mqttv3.IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    synchronized(this@MqttManager) { attempt = 0 }
                    Log.i(TAG, "Conectado a ${config.mqttUri}")
                    onConnectionChanged(true, null)
                    subscribeCmd()
                }
                override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, ex: Throwable?) {
                    val msg = describe(ex)
                    Log.e(TAG, "Falha ao conectar: $msg", ex)
                    onConnectionChanged(false, msg)
                    scheduleReconnect()
                }
            })
        } catch (e: Throwable) {
            val msg = describe(e)
            Log.e(TAG, "Erro de MQTT: $msg", e)
            onConnectionChanged(false, msg)
            scheduleReconnect()
        }
    }

    /** Agenda a próxima tentativa com backoff exponencial (sem empilhar agendamentos). */
    @Synchronized
    private fun scheduleReconnect() {
        if (!wantConnected) return
        pending?.cancel(false)
        val delay = backoffDelayMs(attempt)
        attempt++
        Log.i(TAG, "Reagendando conexão em ${delay}ms (tentativa $attempt)")
        pending = runCatching {
            reconnectExec.schedule({ doConnect() }, delay, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /**
     * Reconexão IMEDIATA disparada por troca de rede celular: o socket atual está preso a uma
     * [Network] que deixou de valer, então reabrimos do zero (com a rede nova) sem esperar backoff.
     */
    @Synchronized
    private fun reconnectNow(reason: String) {
        if (!wantConnected) return
        Log.i(TAG, "Reconexão imediata: $reason")
        attempt = 0
        pending?.cancel(false)
        closeClientQuietly()
        pending = runCatching {
            reconnectExec.schedule({ doConnect() }, 300, TimeUnit.MILLISECONDS)
        }.getOrNull()
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
        wantConnected = false
        synchronized(this) {
            pending?.cancel(false)
            pending = null
        }
        closeClientQuietly()
        cellularCallback?.let { cb ->
            runCatching {
                context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        cellularCallback = null
        cellularNet.set(null)
        runCatching { reconnectExec.shutdownNow() }
    }

    /** Encerra o cliente atual sem lançar e sem bloquear (usado antes de recriar). */
    private fun closeClientQuietly() {
        val c = client ?: return
        client = null
        runCatching { c.setCallback(null) }
        runCatching { if (c.isConnected) c.disconnectForcibly(0, 0) }
        runCatching { c.close(true) }
    }

    /**
     * Registra (uma vez) o callback da rede CELULAR. Mantém [cellularNet] sempre apontando para a
     * rede 4G/USB atual e força reconexão quando a instância troca — para o socket TLS não ficar
     * preso a uma rede morta (causa do "connection abort" intermitente).
     */
    private fun registerCellularCallback() {
        if (cellularCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = cellularNet.getAndSet(network)
                if (prev != network) {
                    // Rede nova (ou trocada): re-amarrar o socket a ela.
                    if (prev != null) reconnectNow("rede celular trocou de instância")
                }
            }
            override fun onLost(network: Network) {
                // Só limpa se for a rede que estávamos usando.
                cellularNet.compareAndSet(network, null)
            }
        }
        runCatching {
            cm.requestNetwork(req, cb)
            cellularCallback = cb
        }.onFailure { Log.w(TAG, "Falha ao registrar callback de rede celular: ${it.message}") }
    }

    /** Aguarda (poll) a rede celular ficar disponível, até [timeoutMs]. */
    private fun awaitCellular(timeoutMs: Long): Network? {
        cellularNet.get()?.let { return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            cellularNet.get()?.let { return it }
        }
        return cellularNet.get()
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
