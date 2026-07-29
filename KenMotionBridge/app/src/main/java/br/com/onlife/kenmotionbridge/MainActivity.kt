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
import br.com.onlife.kenmotionbridge.service.MqttBridgeService
import br.com.onlife.kenmotionbridge.service.RobotBridgeService
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
            // (Re)inicia AMBOS os processos (chassi + :mqtt) e força reconexão com a config atual.
            startBridge(RobotBridgeService.ACTION_RESTART)
        }
        binding.btnStop.setOnClickListener {
            startService(Intent(this, RobotBridgeService::class.java).apply { action = RobotBridgeService.ACTION_STOP })
            startService(Intent(this, MqttBridgeService::class.java).apply { action = RobotBridgeService.ACTION_STOP })
        }
        binding.chkFrontSensor.setOnCheckedChangeListener { _, isChecked ->
            startService(Intent(this, RobotBridgeService::class.java).apply {
                action = RobotBridgeService.ACTION_FRONT_SENSOR
                putExtra("on", isChecked)
            })
        }
        binding.btnForwardSafe.setOnClickListener {
            // FRENTE com gate de nav-ready (SlamwareIntegrationService).
            startService(Intent(this, RobotBridgeService::class.java).apply {
                action = RobotBridgeService.ACTION_FORWARD_SAFE
            })
        }
        binding.btnFrontTest.setOnClickListener {
            // Dispara o cenário "FRENTE 05/07 revisitado" no processo do chassi.
            startService(Intent(this, RobotBridgeService::class.java).apply {
                action = RobotBridgeService.ACTION_FRONT_TEST
            })
            android.widget.Toast.makeText(
                this, "Teste FRENTE iniciado — acompanhe o resultado no log/feedback", android.widget.Toast.LENGTH_LONG
            ).show()
        }
        binding.chkGreeter.setOnCheckedChangeListener { _, isChecked ->
            startService(Intent(this, RobotBridgeService::class.java).apply {
                action = RobotBridgeService.ACTION_GREETER
                putExtra("on", isChecked)
            })
        }
        binding.btnSpeakTest.setOnClickListener {
            startService(Intent(this, RobotBridgeService::class.java).apply {
                action = RobotBridgeService.ACTION_SPEAK_TEST
            })
        }
        binding.btnApps.setOnClickListener {
            startActivity(Intent(this, AppManagerActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        ensureNotificationPermission()
        // Inicia os dois processos da ponte automaticamente assim que a tela abre.
        startBridge(null)

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
                    if (!s.sdkConnected && s.sdkError.isNotBlank()) {
                        binding.txtSdkError.visibility = android.view.View.VISIBLE
                        binding.txtSdkError.text = "↳ ${s.sdkError}"
                    } else {
                        binding.txtSdkError.visibility = android.view.View.GONE
                    }
                    binding.txtSdkInfo.text =
                        "Serviço bound: ${if (s.sdkBound) "sim" else "não"}  |  classe SDK: ${s.sdkClassTried}"
                    binding.txtService.text = "Serviço: ${if (s.serviceRunning) "RODANDO" else "PARADO"}"
                    binding.txtLastCmd.text = "Último comando: ${s.lastCommand}"
                    binding.txtVelocity.text = "v = %.2f m/s    w = %.2f rad/s".format(s.linear, s.angular)
                    binding.txtFront.text = "Dist. frontal: " +
                        if (s.frontCm.isNaN()) "—" else "%.0f cm".format(s.frontCm)
                    binding.txtBattery.text = "Bateria: " +
                        (if (s.batteryPct < 0) "—" else "${s.batteryPct}%") +
                        (if (s.charging) " ⚡" else "")
                    binding.txtPose.text = if (s.poseX.isNaN()) "Pose: —" else
                        "Pose: x=%.2f  y=%.2f  yaw=%.0f°".format(s.poseX, s.poseY, s.poseYawDeg)
                    // Telemetria viva? "SEM SINAL" se a última leitura tem mais de 3 s.
                    val idadeMs = System.currentTimeMillis() - s.telemetryAt
                    binding.txtTelemetry.text = when {
                        s.telemetryAt == 0L -> "Telemetria: —"
                        idadeMs > 3000 -> "Telemetria: SEM SINAL (${idadeMs / 1000}s)"
                        else -> "Telemetria: ativa  |  Loc: ${if (s.localization < 0) "—" else "${s.localization}%"}"
                    }
                    binding.txtNet.text = s.netInfo.ifBlank { "—" }

                    // Percepção (SLAM) — lidar/depth/loc/nav-ready + resultado da frente segura.
                    val lidar = if (s.lidarPts < 0) "—" else "${s.lidarPts}"
                    val depth = if (s.depthPts < 0) "—" else "${s.depthPts}"
                    val loc = if (s.localizationQuality.isNaN()) "—"
                        else "%.2f".format(s.localizationQuality)
                    val nav = if (s.navigationReady) "PRONTA ✅" else "não ⛔"
                    binding.txtPerception.text =
                        "lidar: $lidar pts   depth: $depth pts   loc: $loc   nav: $nav"
                    binding.txtForwardSafe.text = s.lastForwardSafe

                    // Modo Recepção — estado + última saudação.
                    binding.txtGreeter.text = when {
                        !s.greeterEnabled -> "Recepção: desligada"
                        s.lastGreetAt == 0L -> "Recepção: LIGADA — aguardando visitante (≤ 80 cm)"
                        else -> "Recepção: LIGADA — última saudação há %ds%s".format(
                            (System.currentTimeMillis() - s.lastGreetAt) / 1000,
                            if (s.lastGreetDistCm.isNaN()) "" else " (a %.0f cm)".format(s.lastGreetDistCm),
                        )
                    }
                }
            }
        }
    }

    /** Sobe os dois serviços de foreground (processo principal + :mqtt) com a ação opcional. */
    private fun startBridge(action: String?) {
        ContextCompat.startForegroundService(
            this, Intent(this, RobotBridgeService::class.java).apply { action?.let { this.action = it } }
        )
        ContextCompat.startForegroundService(
            this, Intent(this, MqttBridgeService::class.java).apply { action?.let { this.action = it } }
        )
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
