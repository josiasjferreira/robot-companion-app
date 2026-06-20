package br.com.onlife.kenmotionbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Estado vivo compartilhado entre o serviço e a tela de status (fonte única de verdade). */
data class BridgeStatus(
    val brokerConnected: Boolean = false,
    /** Mensagem do último erro/estado do broker (auth, host, TLS…) para exibir na tela. */
    val brokerError: String = "",
    /** Usuário exato enviado ao broker (para conferência na tela). */
    val brokerUser: String = "",
    /** Tamanho da senha enviada (senha mascarada na tela). */
    val brokerPassLen: Int = 0,
    val sdkConnected: Boolean = false,
    /** Motivo exato da falha do SDK/Chassi (não encontrado, bind negado, ClassNotFound…). */
    val sdkError: String = "",
    /** Serviço do RobotSDK realmente bound? */
    val sdkBound: Boolean = false,
    /** Classe do SDK que a reflexão tentou carregar. */
    val sdkClassTried: String = "",
    val lastCommand: String = "—",
    val lastCommandAt: Long = 0L,
    val linear: Double = 0.0,
    val angular: Double = 0.0,
    val frontCm: Double = Double.NaN,
    val batteryPct: Int = -1,
    val serviceRunning: Boolean = false,
    // ── Telemetria Slamware ──────────────────────────────────────────────────
    /** Pose do robô no mapa (metros / graus). NaN se indisponível. */
    val poseX: Double = Double.NaN,
    val poseY: Double = Double.NaN,
    val poseYawDeg: Double = Double.NaN,
    /** Qualidade de localização (0–100), -1 se indisponível. */
    val localization: Int = -1,
    /** Carregando? */
    val charging: Boolean = false,
    /** Epoch (ms) da última telemetria válida lida do chassi (para detectar congelamento). */
    val telemetryAt: Long = 0L,
    /** Último JSON enviado/recebido via AIDL (diagnóstico na tela). */
    val sdkTx: String = "—",
    val sdkRx: String = "—",
)

object StatusBus {
    private val _state = MutableStateFlow(BridgeStatus())
    val state: StateFlow<BridgeStatus> = _state

    /** Atualização ATÔMICA (CAS) — evita corrida entre o loop de controle e o de feedback. */
    fun update(transform: (BridgeStatus) -> BridgeStatus) {
        _state.update(transform)
    }
}
