package br.com.onlife.kenmotionbridge.motion

import android.util.Log
import br.com.onlife.kenmotionbridge.sdk.SlamwareChassis
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cenário de teste CONTROLADO — "FRENTE 05/07 revisitado".
 *
 * Replica, na MESMA ordem, a sequência de comandos que em 05/07/2026 fez o robô
 * INICIAR avanço para frente (mesmo travando por percepção). Não reimplementa
 * nenhuma lógica de movimento: apenas orquestra as funções JÁ existentes da ponte
 * ([SlamwareChassis.chassisCtl], [SlamwareChassis.mapCtl],
 * [SlamwareChassis.trackForward], [SlamwareChassis.moveBy]) e captura telemetria
 * estruturada antes/durante/depois via [SlamwareChassis.diagJson].
 *
 * Sequência EXATA reproduzida (documentada em docs/RELATORIO_SENSORES_MORTOS.md
 * e docs/ANALISE_ROBOSTUDIO_E_MAPA.md):
 *   1. chassis_ctl build_mode   → switchWorkMode(MODE_BUILD_MAP)
 *   2. map begin_map            → clearMap() + beginBuildMap()
 *   3. (opcional) recover_localization
 *   4. trackForward(0.5m)       → moveTo(Location(0.5,0,0), MoveOption{MoveTypeTrack}, 0)
 *   5. moveBy(FORWARD)          → segunda variante (OA), para comparação
 *
 * SEPARAÇÃO CRÍTICA de veredito (não confundir):
 *   - `bridgeOk`  = SUCESSO DE PONTE: todos os comandos foram enviados/aceitos sem
 *     exceção e os estados foram lidos. Isso NÃO significa que o robô andou.
 *   - `sensorsRevived`/`poseAdvanced`/`operatorMoved` = SUCESSO DE HARDWARE: a
 *     percepção voltou e o robô fisicamente avançou. Se a ponte está OK mas o
 *     hardware não, o diagnóstico permanece no LIDAR/depth/odometria do robô.
 */
class FrontMotionTestScenario2026_07_05(
    private val chassis: SlamwareChassis,
) {
    companion object {
        private const val TAG = "FrontMotionTest0705"
        /** Distância do passo TRACK, idêntica ao teste original. */
        private const val TRACK_DIST_M = 0.5f
        /** Janela de observação de cada comando de avanço (ms) e passo do polling. */
        private const val OBSERVE_MS = 4000L
        private const val POLL_MS = 500L
        /** Deslocamento (m) acima do qual consideramos que a pose AVANÇOU de fato. */
        private const val POSE_ADVANCE_M = 0.05
    }

    /** Snapshot de telemetria em um instante do teste (fonte: diagJson + lastForwardStatus). */
    data class Snapshot(
        val phase: String,
        val tRelMs: Long,
        val lidarPts: Int,
        val depthPts: Int,
        val poseX: Double,
        val poseY: Double,
        val poseYaw: Double,
        val localization: Double,
        val workMode: String,
        val naviReady: Boolean,
        val moveStates: String,
        val forwardStatus: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("phase", phase); put("t_rel_ms", tRelMs)
            put("lidar_pts", lidarPts); put("depth_pts", depthPts)
            put("pose", JSONObject().put("x", poseX).put("y", poseY).put("yaw_deg", poseYaw))
            put("localization", localization); put("work_mode", workMode)
            put("navi_ready", naviReady); put("move_states", moveStates)
            put("forward_status", forwardStatus)
        }
    }

    /** Registro estruturado de UM comando enviado (sucesso de PONTE, não de hardware). */
    data class StepLog(val step: String, val command: String, val ok: Boolean, val detail: String, val tRelMs: Long) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("step", step); put("command", command); put("ok", ok)
            put("detail", detail); put("t_rel_ms", tRelMs)
        }
    }

    /** Resultado consolidado do teste. */
    data class FrontMotionTestResult(
        val startedAtMs: Long,
        val steps: List<StepLog>,
        val snapshots: List<Snapshot>,
        val movedFromWaiting: Boolean,   // status saiu de WAITING_FOR_START p/ RUNNING
        val returnedToWaiting: Boolean,  // …e voltou p/ WAITING_FOR_START
        val sensorsRevived: Boolean,     // lidar_pts>0 OU depth_pts>0 em algum snapshot
        val poseAdvanced: Boolean,       // deslocou > POSE_ADVANCE_M
        val bridgeOk: Boolean,           // todos os comandos enviados sem exceção
        var operatorMoved: Boolean? = null,  // preenchido pelo operador (null = não avaliado)
        var operatorNote: String = "",
    ) {
        /** Veredito automático — distingue explicitamente ponte × hardware. */
        val verdict: String
            get() = when {
                !bridgeOk -> "FALHA DE PONTE: algum comando não foi enviado/aceito (ver steps)."
                poseAdvanced || operatorMoved == true ->
                    "SUCESSO: ponte OK e robô AVANÇOU (pose/observação confirmam)."
                sensorsRevived ->
                    "PARCIAL: ponte OK, sensores voltaram a publicar, mas sem avanço confirmado."
                movedFromWaiting && returnedToWaiting ->
                    "PONTE OK, HARDWARE BLOQUEADO: avanço aceitou (RUNNING) e reverteu a " +
                    "WAITING_FOR_START; LIDAR/depth/odometria seguem mortos → problema é do robô."
                else ->
                    "PONTE OK, SEM MOVIMENTO: comandos enviados/aceitos, porém sensores em 0 e " +
                    "sem avanço → percepção/SLAM do robô continua o bloqueio, não a ponte."
            }

        fun toJson(): JSONObject = JSONObject().apply {
            put("type", "front_test_result")
            put("scenario", "FRENTE_05_07_2026")
            put("started_at_ms", startedAtMs)
            put("bridge_ok", bridgeOk)
            put("moved_from_waiting", movedFromWaiting)
            put("returned_to_waiting", returnedToWaiting)
            put("sensors_revived", sensorsRevived)
            put("pose_advanced", poseAdvanced)
            put("operator_moved", operatorMoved ?: JSONObject.NULL)
            put("operator_note", operatorNote)
            put("verdict", verdict)
            put("steps", JSONArray().also { arr -> steps.forEach { arr.put(it.toJson()) } })
            put("snapshots", JSONArray().also { arr -> snapshots.forEach { arr.put(it.toJson()) } })
        }
    }

    private val steps = mutableListOf<StepLog>()
    private val snapshots = mutableListOf<Snapshot>()
    private var t0 = 0L
    private var allOk = true

    private fun now() = System.currentTimeMillis() - t0

    /** Lê um snapshot coeso reaproveitando o diag já implementado na ponte. */
    private fun snap(phase: String) {
        val d = runCatching { chassis.diagJson() }.getOrElse { JSONObject() }
        val pose = d.optJSONObject("pose") ?: JSONObject()
        val s = Snapshot(
            phase = phase,
            tRelMs = now(),
            lidarPts = d.optInt("lidar_pts", 0),
            depthPts = d.optInt("depth_pts", 0),
            poseX = pose.optDouble("x", 0.0),
            poseY = pose.optDouble("y", 0.0),
            poseYaw = pose.optDouble("yaw_deg", 0.0),
            localization = d.optDouble("localization", 0.0),
            workMode = d.optString("work_mode", "?"),
            naviReady = d.optBoolean("navi_ready", false),
            moveStates = d.optString("move_states", "?"),
            forwardStatus = runCatching { chassis.lastForwardStatus() }.getOrDefault(""),
        )
        snapshots += s
        Log.i(TAG, "snap[$phase] lidar=${s.lidarPts} depth=${s.depthPts} " +
            "pose=(${s.poseX},${s.poseY}) loc=${s.localization} navi=${s.naviReady} " +
            "moveStates=${s.moveStates} fwd=${s.forwardStatus}")
    }

    private fun step(name: String, command: String, block: () -> Pair<Boolean, String>) {
        val (ok, detail) = runCatching { block() }.getOrElse { false to "EXCEÇÃO: ${it.message}" }
        if (!ok) allOk = false
        steps += StepLog(name, command, ok, detail, now())
        Log.i(TAG, "step[$name] cmd=$command ok=$ok · $detail")
    }

    /**
     * Observa um comando de avanço por [OBSERVE_MS], amostrando snapshots e o
     * status da ação ([SlamwareChassis.lastForwardStatus]) a cada [POLL_MS].
     */
    private fun observeForward(tag: String) {
        var elapsed = 0L
        var i = 0
        while (elapsed < OBSERVE_MS) {
            snap("${tag}_t${i}")
            Thread.sleep(POLL_MS)
            elapsed += POLL_MS
            i++
        }
    }

    /** Executa a sequência completa e devolve o resultado consolidado. */
    fun run(): FrontMotionTestResult {
        t0 = System.currentTimeMillis()
        Log.i(TAG, "=== INÍCIO do cenário FRENTE 05/07 revisitado ===")

        // 0) Estado ANTES de qualquer comando (baseline).
        snap("antes")

        // 1) Modo de construção de mapa (idêntico ao dia 05/07).
        step("build_mode", "chassis_ctl:build_mode") {
            val r = chassis.chassisCtl("build_mode"); r.contains("OK") to r
        }
        // 2) Iniciar mapa: clearMap + beginBuildMap.
        step("begin_map", "map:begin_map") { chassis.mapCtl("begin_map") }
        // 2b) Habilitar atualização do mapa (mapUpd=true) — no dia estava implícito no modo.
        step("map_update_on", "chassis_ctl:upd_on") {
            val r = chassis.chassisCtl("upd_on"); r.contains("OK") to r
        }
        // 3) Tentar recuperar localização (auxiliar usado para destravar).
        step("recover_localization", "map:recover_localization") { chassis.mapCtl("recover_localization") }

        snap("apos_setup")

        // 4) Avanço TRACK 0.5 m (moveTo MoveTypeTrack) — o comando que disparou o avanço.
        step("track_forward", "trackForward(${TRACK_DIST_M}m)") {
            val r = chassis.trackForward(TRACK_DIST_M)
            r.contains("enviado") to r
        }
        observeForward("track")

        // 5) Segunda variante: moveBy(FORWARD) (modo OA) — para comparação de estado.
        step("moveby_forward", "moveBy(FORWARD)") {
            val ok = chassis.moveBy(SlamwareChassis.Dir.FORWARD); ok to "moveBy=$ok"
        }
        observeForward("moveby")

        // 6) Parada segura — encerra a ação em andamento.
        step("stop", "cancelAction") { chassis.cancelAction(); true to "cancelAction chamado" }
        snap("depois")

        Log.i(TAG, "=== FIM do cenário — consolidando ===")
        return consolidate()
    }

    private fun consolidate(): FrontMotionTestResult {
        // Transições de status observadas na sequência de snapshots.
        val statuses = snapshots.map { it.forwardStatus.uppercase() }
        val sawRunning = statuses.any { it.contains("RUNNING") }
        val sawWaiting = statuses.any { it.contains("WAITING_FOR_START") }
        // "voltou para WAITING": um RUNNING seguido, mais tarde, de um WAITING.
        var movedFromWaiting = false
        var returnedToWaiting = false
        var seenRunning = false
        for (st in statuses) {
            if (st.contains("RUNNING")) { seenRunning = true; movedFromWaiting = true }
            if (seenRunning && st.contains("WAITING_FOR_START")) returnedToWaiting = true
        }
        // Se nunca vimos RUNNING mas vimos WAITING, registra que não saiu do WAITING.
        if (!sawRunning && sawWaiting) movedFromWaiting = false

        val sensorsRevived = snapshots.any { it.lidarPts > 0 || it.depthPts > 0 }

        // Avanço de pose: maior distância da origem (primeiro snapshot) a qualquer outro.
        val base = snapshots.firstOrNull()
        val poseAdvanced = base != null && snapshots.any {
            val dx = it.poseX - base.poseX
            val dy = it.poseY - base.poseY
            Math.sqrt(dx * dx + dy * dy) > POSE_ADVANCE_M
        }

        val result = FrontMotionTestResult(
            startedAtMs = t0,
            steps = steps.toList(),
            snapshots = snapshots.toList(),
            movedFromWaiting = movedFromWaiting,
            returnedToWaiting = returnedToWaiting,
            sensorsRevived = sensorsRevived,
            poseAdvanced = poseAdvanced,
            bridgeOk = allOk,
        )
        Log.i(TAG, "VEREDITO: ${result.verdict}")
        return result
    }
}
