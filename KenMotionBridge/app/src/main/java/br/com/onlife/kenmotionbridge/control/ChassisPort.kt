package br.com.onlife.kenmotionbridge.control

import br.com.onlife.kenmotionbridge.sdk.SlamwareChassis

/**
 * Seam de BAIXO NÍVEL do chassi (Fase 1 da arquitetura em
 * docs/ARQUITETURA_CONTROLE_EMY.md). Abstrai as primitivas de movimento do núcleo
 * Slamware (TCP 1445) atrás de uma interface, para que o futuro `ActionScheduler`
 * dependa de um contrato — não da classe concreta.
 *
 * Fase 1 = extração pura: [SlamwareChassis] JÁ implementa todos estes membros;
 * aqui apenas nomeamos o contrato. NENHUM comportamento novo. Reusa
 * [SlamwareChassis.Dir] como vocabulário de direção (promovível a enum próprio
 * numa fase posterior).
 */
interface ChassisPort {
    /** Sessão direta com o núcleo ativa? */
    val connected: Boolean

    /** Passo discreto na direção (moveBy(MoveDirection)). @return enviado com sucesso. */
    fun moveBy(dir: SlamwareChassis.Dir): Boolean

    /** Giro relativo por ângulo em graus (rotate(Rotation)). @return enviado. */
    fun rotate(graus: Float): Boolean

    /** Avanço por odometria (moveTo + MoveTypeTrack). @return status/erro textual. */
    fun trackForward(distM: Float): String

    /** Aplica velocidade (no‑op degradado neste SDK; mantido pelo contrato). */
    fun sendVelocity(linear: Double, angular: Double)

    /** Cancela a ação de movimento em andamento (IMoveAction.cancel). */
    fun cancelAction()

    /** Estado+motivo da última tentativa de FRENTE (ActionStatus · reason). */
    fun lastForwardStatus(): String
}
