package br.com.onlife.kenmotionbridge.mqtt

import android.content.Context
import android.net.Network
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
 * Split de rotas (dual-homing): quando o serviço amarra o PROCESSO à rede do chassi (que não tem
 * internet), ele passa em [connect] a rede de INTERNET e `bindMqtt=true`; então o socket do MQTT
 * é amarrado a essa rede via [InternetSslSocketFactory]/[ReResolvingSocket] (DNS e saída pela rede
 * de internet), mantendo o hostname na URI — SNI e verificação TLS intactos.
 *
 * Reconexão robusta: em vez do auto-reconnect do Paho — que reaproveita a mesma `socketFactory`
 * com uma instância de [Network] capturada UMA vez — fazemos a reconexão por conta própria, com
 * backoff exponencial, reconstruindo cliente + `socketFactory` a cada tentativa.
 */
class MqttManager(
    private val context: Context,
    /**
     * Config MUTÁVEL: o service injeta a config recém-salva antes de reconectar
     * (BUG corrigido: o manager ficava preso à config do boot do processo e
     * reconectava com host/senha VELHOS mesmo após salvar nas Configurações).
     */
    @Volatile var config: BridgeConfig,
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

    /**
     * Rede de INTERNET escolhida pelo serviço (capturada ANTES do bind do processo). Quando o
     * processo está roteado para a rede do chassi, o socket do MQTT é amarrado a esta rede.
     */
    @Volatile private var internetNet: Network? = null

    /** Se true, amarra o socket do MQTT a [internetNet] (cenário dual-homing). */
    @Volatile private var bindMqtt = false

    /** Verdadeiro entre [connect] e [disconnect]; controla se devemos reagendar reconexões. */
    @Volatile private var wantConnected = false

    /** Contador do backoff; zerado a cada conexão bem-sucedida. */
    private var attempt = 0

    private val reconnectExec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mqtt-reconnect").apply { isDaemon = true }
        }
    private var pending: ScheduledFuture<*>? = null

    /**
     * @param internetNet rede com internet a usar no socket (Wi-Fi/4G/USB), ou null p/ rota padrão.
     * @param bindMqtt    quando true, amarra o socket a [internetNet] (processo roteado p/ o chassi).
     */
    fun connect(internetNet: Network? = null, bindMqtt: Boolean = false) {
        wantConnected = true
        this.internetNet = internetNet
        this.bindMqtt = bindMqtt && internetNet != null
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
                    // Split de rotas: amarra o socket TLS à rede de INTERNET escolhida pelo serviço,
                    // de modo que funcione mesmo com o processo roteado para a rede do chassi.
                    val net = internetNet
                    socketFactory = if (bindMqtt && net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val host = BridgeConfig.hostFromUri(config.mqttUri)
                        val port = BridgeConfig.portFromUri(config.mqttUri)
                        Log.i(TAG, "MQTT amarrado à rede de INTERNET ($net) para $host:$port")
                        InternetSslSocketFactory(net, base, host, port)
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
     * Reconexão IMEDIATA (sem backoff) — usada quando a rede de internet troca de instância: o
     * socket atual pode estar preso a uma rede que deixou de valer. Reabre do zero. Se ainda não
     * havia sido conectado, apenas inicia a conexão.
     */
    @Synchronized
    fun reconnectNow(reason: String) {
        if (!wantConnected) { connect(); return }
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
        internetNet = null
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
