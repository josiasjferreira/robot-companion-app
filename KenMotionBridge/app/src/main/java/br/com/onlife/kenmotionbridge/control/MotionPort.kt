package br.com.onlife.kenmotionbridge.control

/**
 * Seam de ALTO NÍVEL de movimento (Fase 1 da arquitetura em
 * docs/ARQUITETURA_CONTROLE_EMY.md). Contrato de intenções de deslocamento que o
 * `ActionScheduler` proposto consumirá, independente do transporte (Slamware
 * direto ou fallback CSJBot).
 *
 * Fase 1 = extração pura: `KenMotionSdk` JÁ implementa estes três membros; aqui
 * só nomeamos o contrato. NENHUM comportamento novo.
 *
 * Nota de escopo: o `BodyPort` (cabeça/expressão/gesto) da proposta fica para a
 * Fase 3 — não há código de corpo a extrair hoje (o CT300‑H é robô de bandeja e
 * o SDK expõe cabeça/braço só por reflexão ainda não usada). Ver
 * docs/ARQUITETURA_CONTROLE_EMY.md §4.
 */
interface MotionPort {
    /** Chassi apto a receber comando de movimento agora? */
    fun isMotionAvailable(): Boolean

    /**
     * Avança para frente. [distanceMeters] vira duração (t=d/v) — o firmware não
     * anda por distância; [speed] em m/s. Ambos opcionais (usa padrões).
     */
    fun moveForward(distanceMeters: Float? = null, speed: Float? = null)

    /** Parada segura — cancela navegação + zera velocidades nos dois caminhos. */
    fun stop()
}
