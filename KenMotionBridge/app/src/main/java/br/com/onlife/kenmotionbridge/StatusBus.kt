package br.com.onlife.kenmotionbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado vivo compartilhado entre o serviço e a tela de status. */
data class BridgeStatus(
    val brokerConnected: Boolean = false,
    val sdkConnected: Boolean = false,
    val lastCommand: String = "—",
    val lastCommandAt: Long = 0L,
    val linear: Double = 0.0,
    val angular: Double = 0.0,
    val frontCm: Double = Double.NaN,
    val batteryPct: Int = -1,
    val serviceRunning: Boolean = false,
)

object StatusBus {
    private val _state = MutableStateFlow(BridgeStatus())
    val state: StateFlow<BridgeStatus> = _state

    fun update(transform: (BridgeStatus) -> BridgeStatus) {
        _state.value = transform(_state.value)
    }
}
