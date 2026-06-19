package br.com.onlife.kenmotionbridge.mqtt

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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
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
 */
class MqttManager(
    private val config: BridgeConfig,
    /** (conectado, mensagemDeErro?) — erro só vem preenchido quando conectado=false. */
    private val onConnectionChanged: (Boolean, String?) -> Unit,
    private val onCommand: (String) -> Unit,
) {
    companion object { private const val TAG = "MqttManager" }

    private var client: MqttAsyncClient? = null

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
                if (config.mqttUser.isNotEmpty()) userName = config.mqttUser
                if (config.mqttPassword.isNotEmpty()) password = config.mqttPassword.toCharArray()
                // TLS habilitado para ssl:// e wss:// (SSLSocketFactory padrão do sistema).
                if (config.mqttUri.startsWith("ssl") || config.mqttUri.startsWith("wss")) {
                    socketFactory = buildSslContext().socketFactory
                }
            }

            Log.i(TAG, "Conectando a ${config.mqttUri} (user=${config.mqttUser})")

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
