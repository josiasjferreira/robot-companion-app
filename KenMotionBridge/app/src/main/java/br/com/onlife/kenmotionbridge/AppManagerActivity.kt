package br.com.onlife.kenmotionbridge

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Gerenciador de apps — permite SAIR de um app em modo quiosque (ex.: o app
 * nativo "Amy"/diningcar do robô que trava o tablet) e abrir o CsjRobotStudio.
 *
 * LIMITE HONESTO do Android: um app comum NÃO pode "matar" outro app em
 * foreground (isso é privilégio de system/root). O que dá para fazer:
 *  - ABRIR qualquer app instalado (traz para frente — resolve o quiosque na
 *    prática, pois o app escolhido assume a tela);
 *  - abrir a tela de INFORMAÇÕES do app (onde há o botão "Forçar parada" que o
 *    operador toca manualmente);
 *  - best-effort killBackgroundProcesses (só mata processos em 2º plano).
 */
class AppManagerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(title("Apps / Encerrar"))
        root.addView(hint(
            "Para sair de um app travado (quiosque): toque em ABRIR no app desejado " +
            "(ex.: CsjRobotStudio) — ele assume a tela. Para forçar parada de um app " +
            "(ex.: Amy/diningcar), toque em ENCERRAR e use \"Forçar parada\" na tela do sistema."
        ))

        // Atalhos rápidos.
        root.addView(rowButton("⚙ Configurações do Android") {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        })
        root.addView(rowButton("🏠 Trocar app inicial (launcher padrão)") {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        })
        root.addView(spacer())

        // Lista dinâmica de apps lançáveis (nome + Abrir + Encerrar).
        val apps = launchableApps()
        root.addView(title("Apps instalados (${apps.size})"))
        for (app in apps) root.addView(appRow(app.label, app.pkg))
    }

    private data class AppEntry(val label: String, val pkg: String)

    /** Apps com launcher, com os de interesse (csjbot/slamtec/robo) no topo. */
    private fun launchableApps(): List<AppEntry> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = runCatching {
            pm.queryIntentActivities(main, 0).map {
                AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName)
            }
        }.getOrDefault(emptyList())
            .distinctBy { it.pkg }
        fun rank(p: String) = when {
            p.contains("robotstation") || p.contains("robostudio") || p.contains("studio") -> 0
            p.startsWith("com.csjbot") || p.startsWith("com.slamtec") -> 1
            else -> 2
        }
        return list.sortedWith(compareBy({ rank(it.pkg) }, { it.label.lowercase() }))
    }

    private fun appRow(label: String, pkg: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val txt = TextView(this).apply {
            text = "$label\n$pkg"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val abrir = Button(this).apply {
            text = "Abrir"
            setOnClickListener { launchApp(pkg) }
        }
        val encerrar = Button(this).apply {
            text = "Encerrar"
            setOnClickListener { stopApp(pkg) }
        }
        row.addView(txt); row.addView(abrir); row.addView(encerrar)
        return row
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Sem tela inicial para $pkg", Toast.LENGTH_SHORT).show()
        }
    }

    /** Best-effort kill em 2º plano + abre a tela de info do app (Forçar parada manual). */
    private fun stopApp(pkg: String) {
        runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
        }
        Toast.makeText(
            this, "Toque em \"Forçar parada\" na tela do sistema", Toast.LENGTH_LONG
        ).show()
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }
    }

    // ── Helpers de UI ─────────────────────────────────────────────────────────

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(Color.parseColor("#8B98A5"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun rowButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ); setOnClickListener { onClick() }
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
