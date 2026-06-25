package br.com.onlife.kenmotionbridge.mqtt

import android.util.Log
import br.com.onlife.kenmotionbridge.BridgeConfig
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Cliente MQTT (Paho) ligado ao MESMO broker do app web (HiveMQ Cloud, TLS).
 * Suporta `ssl://` (MQTT/TLS nativo) e `wss://` (WebSocket Secure).
 */
class MqttManager(
    private val config: BridgeConfig,
    private val onConnectionChanged: (Boolean) -> Unit,
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
                isAutomaticReconnect = true
                isCleanSession = config.cleanSession
                keepAliveInterval = config.keepAliveSec
                connectionTimeout = 10
                if (config.mqttUser.isNotEmpty()) userName = config.mqttUser
                if (config.mqttPassword.isNotEmpty()) password = config.mqttPassword.toCharArray()
                if (config.mqttUri.startsWith("ssl") || config.mqttUri.startsWith("wss")) {
                    socketFactory = buildSslContext().socketFactory
                }
            }

            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "Conectado (reconnect=$reconnect) a $serverURI")
                    onConnectionChanged(true)
                    subscribeCmd()
                }
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Conexão perdida: ${cause?.message}")
                    onConnectionChanged(false)
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
                    Log.e(TAG, "Falha ao conectar: ${ex?.message}")
                    onConnectionChanged(false)
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Erro de MQTT: ${e.message}", e)
            onConnectionChanged(false)
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
