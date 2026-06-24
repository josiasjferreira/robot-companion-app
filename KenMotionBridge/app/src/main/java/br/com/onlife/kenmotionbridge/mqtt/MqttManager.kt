package br.com.onlife.kenmotionbridge.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
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
    private var internetCallback: ConnectivityManager.NetworkCallback? = null

    /** Rede de INTERNET ATUAL (Wi-Fi/4G/USB) para o socket do MQTT. null = indisponível. */
    private val internetNet = AtomicReference<Network?>()

    /**
     * Amarrar o MQTT à rede de internet é necessário quando o processo está roteado para outra
     * rede (dual-homing: processo na Ethernet do chassi) OU quando se força a saída pela celular.
     */
    private val bindInternet: Boolean get() = config.dualHoming || config.mqttForceCellular

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
        if (bindInternet) registerInternetCallback()
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
                    // Split de rotas: amarra o socket TLS à rede de INTERNET (Wi-Fi/4G/USB), de
                    // modo que funcione mesmo com o processo roteado para a Ethernet do chassi.
                    socketFactory = if (bindInternet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val net = awaitInternet(6000)
                        if (net != null) {
                            val host = BridgeConfig.hostFromUri(config.mqttUri)
                            val port = BridgeConfig.portFromUri(config.mqttUri)
                            Log.i(TAG, "MQTT amarrado à rede de INTERNET ($net) para $host:$port")
                            InternetSslSocketFactory(net, base, host, port)
                        } else {
                            Log.w(TAG, "bindInternet ligado, mas rede de internet indisponível — usando rota padrão")
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
        internetCallback?.let { cb ->
            runCatching {
                context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        internetCallback = null
        internetNet.set(null)
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
     * Registra (uma vez) o callback da rede de INTERNET. Mantém [internetNet] apontando para a
     * rede atual (Wi-Fi/4G/USB) e força reconexão quando a instância troca — para o socket TLS não
     * ficar preso a uma rede morta (causa do "connection abort" intermitente). Se [config.mqttForceCellular]
     * estiver ligado, restringe a busca à rede CELULAR; senão aceita qualquer rede com internet.
     */
    private fun registerInternetCallback() {
        if (internetCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (config.mqttForceCellular) builder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = internetNet.getAndSet(network)
                if (prev != null && prev != network) {
                    // Rede nova (ou trocada): re-amarrar o socket a ela.
                    reconnectNow("rede de internet trocou de instância")
                }
            }
            override fun onLost(network: Network) {
                // Só limpa se for a rede que estávamos usando.
                internetNet.compareAndSet(network, null)
            }
        }
        runCatching {
            cm.requestNetwork(builder.build(), cb)
            internetCallback = cb
        }.onFailure { Log.w(TAG, "Falha ao registrar callback de rede de internet: ${it.message}") }
    }

    /** Aguarda (poll) a rede de internet ficar disponível, até [timeoutMs]. */
    private fun awaitInternet(timeoutMs: Long): Network? {
        internetNet.get()?.let { return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            internetNet.get()?.let { return it }
        }
        return internetNet.get()
    }

    /**
     * SSLSocketFactory que amarra o transporte do MQTT a uma [network] de internet específica e o
     * envelopa em TLS, mantendo o HOSTNAME na URI (logo SNI e verificação de certificado seguem
     * corretos). O socket base é um [ReResolvingSocket]: ele resolve o DNS e egressa **pela rede de
     * internet**, mesmo que o PROCESSO esteja roteado para outra rede (Ethernet do chassi).
     *
     * O Paho usa `createSocket()` (sem args) e depois chama `connect()` no SSLSocket, que delega ao
     * socket base — por isso o [ReResolvingSocket] intercepta a resolução/saída no momento certo.
     */
    private class InternetSslSocketFactory(
        private val network: Network,
        private val delegate: SSLSocketFactory,
        private val host: String,
        private val port: Int,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(): Socket =
            delegate.createSocket(ReResolvingSocket(network), host, port, true)
        override fun createSocket(h: String?, p: Int): Socket =
            delegate.createSocket(ReResolvingSocket(network), host, port, true)
        override fun createSocket(h: String?, p: Int, lh: InetAddress?, lp: Int): Socket =
            delegate.createSocket(ReResolvingSocket(network), host, port, true)
        override fun createSocket(a: InetAddress?, p: Int): Socket =
            delegate.createSocket(ReResolvingSocket(network), host, port, true)
        override fun createSocket(a: InetAddress?, p: Int, lh: InetAddress?, lp: Int): Socket =
            delegate.createSocket(ReResolvingSocket(network), host, port, true)
        override fun createSocket(s: Socket?, h: String?, p: Int, autoClose: Boolean): Socket =
            delegate.createSocket(s, h, p, autoClose)
    }

    /**
     * Socket cuja resolução de nome e saída são forçadas para uma [network] específica. Ao conectar,
     * resolve o host via DNS ESCOPADO da rede ([Network.getAllByName]) e amarra o próprio socket a
     * ela ([Network.bindSocket]) — assim o MQTT alcança a internet mesmo com o processo amarrado à
     * Ethernet do chassi (cujo DNS/rota não chegam à internet).
     */
    private class ReResolvingSocket(private val network: Network) : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val isa = endpoint as InetSocketAddress
            val name = isa.hostString ?: isa.address?.hostAddress ?: throw UnknownHostException("host nulo")
            val resolved = network.getAllByName(name).firstOrNull() ?: throw UnknownHostException(name)
            if (!isBound) runCatching { bind(InetSocketAddress(0)) }   // cria o fd local
            runCatching { network.bindSocket(this) }                   // egresso pela rede de internet
            super.connect(InetSocketAddress(resolved, isa.port), timeout)
        }
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
