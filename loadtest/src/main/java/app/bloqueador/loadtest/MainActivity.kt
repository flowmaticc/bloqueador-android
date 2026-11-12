package app.bloqueador.loadtest

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bloqueador.loadtest.LoadTest.enabled
import app.bloqueador.loadtest.LoadTest.intervalMinutes

/**
 * Pantalla mínima: iniciar/parar y ver las últimas líneas del registro.
 * Por adb: `am start -n app.bloqueador.loadtest/.MainActivity --es accion iniciar --ei minutos 15`
 * (o `--es accion parar`).
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("accion")) {
            "iniciar" -> LoadTest.start(this, intent.getIntExtra("minutos", LoadTest.DEFAULT_MINUTES))
            "parar" -> LoadTest.stop(this)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { setTextIsSelectable(true); textSize = 12f }
        val toggle = Button(this)
        fun refresh() {
            toggle.text = if (enabled) "Parar" else "Iniciar (cada ${LoadTest.DEFAULT_MINUTES} min)"
            val lines = LoadTest.logFile(this).takeIf { it.exists() }?.readLines().orEmpty()
            status.text = buildString {
                append(if (enabled) "En marcha, cada $intervalMinutes min" else "Parada")
                append(" · ${(lines.size - 1).coerceAtLeast(0)} ciclos\n\n")
                lines.takeLast(30).forEach { append(it).append('\n') }
            }
        }
        toggle.setOnClickListener {
            if (enabled) LoadTest.stop(this) else LoadTest.start(this, LoadTest.DEFAULT_MINUTES)
            refresh()
        }
        refresh()

        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad, pad, pad, pad)
                        addView(toggle)
                        addView(status)
                    },
                )
            },
        )
    }
}
