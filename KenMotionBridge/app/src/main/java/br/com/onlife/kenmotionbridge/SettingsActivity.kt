package br.com.onlife.kenmotionbridge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import br.com.onlife.kenmotionbridge.databinding.ActivitySettingsBinding
import br.com.onlife.kenmotionbridge.service.BridgeService

/**
 * Tela de configurações do broker MQTT (HOST / porta / usuário / senha).
 * Os campos vêm pré-preenchidos com os valores efetivos atuais (settings salvas
 * ou, na ausência delas, os defaults do nosso broker em assets/bridge_config.json).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pré-preenche com a config efetiva atual.
        val cfg = BridgeConfig.load(this)
        binding.editHost.setText(BridgeConfig.hostFromUri(cfg.mqttUri))
        binding.editPort.setText(BridgeConfig.portFromUri(cfg.mqttUri).toString())
        binding.editUser.setText(cfg.mqttUser)
        binding.editPass.setText(cfg.mqttPassword)

        // Comandos AIDL de movimento (msg_id + inteiros das direções).
        binding.editMoveMsg.setText(cfg.moveMsgId)
        binding.editDirFwd.setText(cfg.dirForward.toString())
        binding.editDirBack.setText(cfg.dirBackward.toString())
        binding.editDirLeft.setText(cfg.dirLeft.toString())
        binding.editDirRight.setText(cfg.dirRight.toString())

        binding.btnSave.setOnClickListener { saveAndRestart() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun saveAndRestart() {
        val host = binding.editHost.text.toString().trim()
        if (host.isEmpty()) {
            Toast.makeText(this, "Informe o host do HiveMQ", Toast.LENGTH_SHORT).show()
            return
        }
        val port = binding.editPort.text.toString().trim().toIntOrNull() ?: 8883
        val user = binding.editUser.text.toString().trim()
        val pass = binding.editPass.text.toString().trim()

        BridgeConfig.saveSettings(this, host, port, user, pass)

        // Comandos AIDL de movimento (configuráveis na UI; default vazio mantém o do asset).
        val moveMsg = binding.editMoveMsg.text.toString().trim().ifEmpty { "NAVI_ROBOT_MOVE_REQ" }
        BridgeConfig.saveMotionCmd(
            this, moveMsg,
            binding.editDirFwd.text.toString().trim().toIntOrNull() ?: 0,
            binding.editDirBack.text.toString().trim().toIntOrNull() ?: 1,
            binding.editDirLeft.text.toString().trim().toIntOrNull() ?: 2,
            binding.editDirRight.text.toString().trim().toIntOrNull() ?: 3,
        )

        // Reinicia a ponte para aplicar imediatamente (reconecta ao broker).
        ContextCompat.startForegroundService(
            this,
            Intent(this, BridgeService::class.java).apply { action = BridgeService.ACTION_RESTART }
        )
        Toast.makeText(this, "Configurações salvas — reconectando…", Toast.LENGTH_SHORT).show()
        finish()
    }
}
