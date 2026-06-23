package br.com.onlife.kenmotionbridge.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cobre o backoff exponencial usado na reconexão própria do [MqttManager]. */
class MqttBackoffTest {

    @Test
    fun backoff_cresceExponencialmente() {
        assertEquals(2_000L, MqttManager.backoffDelayMs(0))
        assertEquals(4_000L, MqttManager.backoffDelayMs(1))
        assertEquals(8_000L, MqttManager.backoffDelayMs(2))
        assertEquals(16_000L, MqttManager.backoffDelayMs(3))
    }

    @Test
    fun backoff_respeitaOTeto() {
        assertEquals(30_000L, MqttManager.backoffDelayMs(4))
        assertEquals(30_000L, MqttManager.backoffDelayMs(10))
        assertEquals(30_000L, MqttManager.backoffDelayMs(1000))
    }

    @Test
    fun backoff_naoQuebraComTentativaNegativa() {
        // Defensivo: nunca devolve atraso negativo nem dispara overflow.
        assertEquals(2_000L, MqttManager.backoffDelayMs(-1))
        assertEquals(2_000L, MqttManager.backoffDelayMs(Int.MIN_VALUE))
    }
}
