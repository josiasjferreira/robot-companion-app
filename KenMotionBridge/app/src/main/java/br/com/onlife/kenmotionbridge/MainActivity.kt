package br.com.onlife.kenmotionbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import br.com.onlife.kenmotionbridge.databinding.ActivityMainBinding
import br.com.onlife.kenmotionbridge.service.BridgeService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Tela mínima de status: broker, SDK, último comando, v/w/distância. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRestart.setOnClickListener {
            ensureNotificationPermission()
            // (Re)inicia a ponte e força reconexão ao broker com a config atual.
            ContextCompat.startForegroundService(
                this,
                Intent(this, BridgeService::class.java).apply { action = BridgeService.ACTION_RESTART }
            )
        }
        binding.btnStop.setOnClickListener {
            startService(Intent(this, BridgeService::class.java).apply { action = BridgeService.ACTION_STOP })
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        ensureNotificationPermission()
        // Inicia a ponte e conecta ao broker automaticamente assim que a tela abre.
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))

        observeStatus()
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                StatusBus.state.collectLatest { s ->
                    binding.txtBroker.text = "Broker MQTT: ${onOff(s.brokerConnected)}"
                    if (!s.brokerConnected && s.brokerError.isNotBlank()) {
                        binding.txtBrokerError.visibility = android.view.View.VISIBLE
                        binding.txtBrokerError.text = "↳ ${s.brokerError}"
                    } else {
                        binding.txtBrokerError.visibility = android.view.View.GONE
                    }
                    // Usuário EXATO enviado ao broker (entre aspas p/ revelar espaços) + senha mascarada.
                    binding.txtBrokerAuth.text =
                        "Auth: user='${s.brokerUser}' (${s.brokerUser.length}) senha=${"•".repeat(s.brokerPassLen)} (${s.brokerPassLen})"
                    binding.txtSdk.text = "SDK / Chassi: ${onOff(s.sdkConnected)}"
                    binding.txtService.text = "Serviço: ${if (s.serviceRunning) "RODANDO" else "PARADO"}"
                    binding.txtLastCmd.text = "Último comando: ${s.lastCommand}"
                    binding.txtVelocity.text = "v = %.2f m/s    w = %.2f rad/s".format(s.linear, s.angular)
                    binding.txtFront.text = "Dist. frontal: " +
                        if (s.frontCm.isNaN()) "—" else "%.0f cm".format(s.frontCm)
                    binding.txtBattery.text = "Bateria: " +
                        if (s.batteryPct < 0) "—" else "${s.batteryPct}%"
                }
            }
        }
    }

    private fun onOff(v: Boolean) = if (v) "CONECTADO ✅" else "DESCONECTADO ❌"

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10
            )
        }
    }
}
