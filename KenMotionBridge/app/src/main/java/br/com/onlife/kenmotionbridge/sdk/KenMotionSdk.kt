package br.com.onlife.kenmotionbridge.sdk

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * KenMotionSdk — camada central de **controle de movimento do chassi**, agora 100% via
 * AIDL ([RobotChassis] → RobotSdkService). Não usa SDK in-process (sem Slamware/CSJBot
 * carregados no processo do bridge).
 *
 * Porta de entrada única: inicializarConexaoRobo / moverFrente / moverTras /
 * virarEsquerda / virarDireita / pararMovimento.
 */
class KenMotionSdk(private val chassis: RobotChassis?) {

    companion object { private const val TAG = "KenMotionSdk" }

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /** A conexão é estabelecida pelo BridgeService via RobotChassis.connect(); aqui só refletimos o estado. */
    fun inicializarConexaoRobo(): Boolean {
        val ok = estaConectado()
        Log.i(TAG, "inicializarConexaoRobo — conectado=$ok")
        return ok
    }

    fun estaConectado(): Boolean = chassis?.connected == true

    fun moverFrente(duracaoMs: Long? = null) = comando("moverFrente", RobotChassis.Dir.FORWARD, duracaoMs)
    fun moverTras(duracaoMs: Long? = null) = comando("moverTras", RobotChassis.Dir.BACKWARD, duracaoMs)
    fun virarEsquerda(duracaoMs: Long? = null) = comando("virarEsquerda", RobotChassis.Dir.LEFT, duracaoMs)
    fun virarDireita(duracaoMs: Long? = null) = comando("virarDireita", RobotChassis.Dir.RIGHT, duracaoMs)

    fun pararMovimento() {
        Log.i(TAG, "pararMovimento — enviando comando de parada ao robô…")
        if (chassis == null) { Log.w(TAG, "pararMovimento ignorado — sem chassi"); return }
        chassis.sendStop()
    }

    private fun comando(api: String, dir: RobotChassis.Dir, duracaoMs: Long?) {
        Log.i(TAG, "Chamado $api (dir=$dir, duracaoMs=$duracaoMs)")
        val c = chassis
        if (c == null) { Log.e(TAG, "Erro ao enviar comando: $api — sem chassi"); return }
        if (!c.connected) {
            Log.e(TAG, "Erro ao enviar comando: $api ignorado — chassi não conectado (aguardando connectToSDKSucceed)")
            return
        }
        Log.i(TAG, "Enviando comando de movimento ao robô (AIDL): move($dir)")
        if (!c.move(dir)) Log.e(TAG, "Erro ao enviar comando: move($dir) falhou")
        if (duracaoMs != null && duracaoMs > 0) {
            scheduler.schedule({
                Log.i(TAG, "Duração de ${duracaoMs}ms expirou — parando")
                pararMovimento()
            }, duracaoMs, TimeUnit.MILLISECONDS)
        }
    }

    fun liberar() {
        runCatching { scheduler.shutdownNow() }
    }
}
