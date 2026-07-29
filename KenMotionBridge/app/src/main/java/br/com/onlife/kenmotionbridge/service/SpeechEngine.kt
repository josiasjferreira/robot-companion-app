package br.com.onlife.kenmotionbridge.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Motor de fala — aciona o ALTO-FALANTE do robô.
 *
 * Prioriza o TTS NATIVO do robô (CSJBot `Robot.startSpeaking`, via reflexão —
 * assinaturas confirmadas por javap: `initSpeak(Context,int)` +
 * `startSpeaking(String, OnSpeakListener)`), que sai pelo alto-falante do robô.
 * Se o SDK do fabricante não estiver disponível, cai para o `TextToSpeech` do
 * Android (pt-BR) no alto-falante do tablet. Nunca lança — degrada e loga.
 */
class SpeechEngine(private val context: Context) {

    companion object { private const val TAG = "SpeechEngine" }

    @Volatile private var robotInited = false
    private var androidTts: TextToSpeech? = null
    @Volatile private var androidReady = false

    init { initAndroidTts() }

    /** Fala [text]. Tenta o robô; se não der, usa o TTS do Android. */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (speakViaRobot(text)) {
            Log.i(TAG, "fala via robô: ${text.take(40)}…")
            return
        }
        speakViaAndroid(text)
    }

    fun isSpeaking(): Boolean = runCatching {
        val robot = robotInstance() ?: return androidTts?.isSpeaking ?: false
        robot.javaClass.getMethod("isSpeaking").invoke(robot) as? Boolean ?: false
    }.getOrDefault(false)

    // ── Robô (CSJBot) ─────────────────────────────────────────────────────────

    private fun robotInstance(): Any? = runCatching {
        Class.forName("com.csjbot.coshandler.core.Robot").getMethod("getInstance").invoke(null)
    }.getOrNull()

    private fun speakViaRobot(text: String): Boolean = runCatching {
        val robot = robotInstance() ?: return false
        if (!robotInited) {
            runCatching {
                robot.javaClass.getMethod("initSpeak", Context::class.java, Int::class.javaPrimitiveType)
                    .invoke(robot, context, 0)
            }.onFailure { Log.w(TAG, "initSpeak falhou (segue): ${it.message}") }
            robotInited = true
        }
        val listenerCls = Class.forName("com.csjbot.coshandler.listener.OnSpeakListener")
        robot.javaClass.getMethod("startSpeaking", String::class.java, listenerCls)
            .invoke(robot, text, null)
        true
    }.getOrElse {
        Log.w(TAG, "startSpeaking do robô indisponível: ${it.cause?.message ?: it.message}")
        false
    }

    // ── Android (fallback) ────────────────────────────────────────────────────

    private fun initAndroidTts() {
        androidTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { androidTts?.language = Locale("pt", "BR") }
                androidReady = true
                Log.i(TAG, "TextToSpeech Android pronto (pt-BR)")
            } else {
                Log.w(TAG, "TextToSpeech Android indisponível (status=$status)")
            }
        }
    }

    private fun speakViaAndroid(text: String) {
        if (!androidReady) { Log.w(TAG, "TTS Android não pronto — fala perdida"); return }
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kenmotion-speak")
        Log.i(TAG, "fala via Android TTS: ${text.take(40)}…")
    }

    fun release() {
        runCatching { androidTts?.stop(); androidTts?.shutdown() }
        androidTts = null; androidReady = false
    }
}
