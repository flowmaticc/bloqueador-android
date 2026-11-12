package app.bloqueador.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.bloqueador.BloqueadorApp
import app.bloqueador.apps.AppStrategy

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }

    companion object {
        /** Abrir el historial con el filtro "Amenazas" (desde el aviso de sitio peligroso). */
        const val EXTRA_OPEN_THREATS = "open_threats"
    }
}

private enum class Screen { ONBOARDING, HOME, SETTINGS, STRATEGY, APPS, HISTORY }

/** Navegación mínima entre pantallas (sin librería: son cinco pantallas en línea). */
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val settings = BloqueadorApp.instance.settings
    // Desde la notificación de nueva versión se abre directamente Ajustes → Actualizaciones de la app.
    val fromUpdate = remember {
        (context as? android.app.Activity)?.intent
            ?.getBooleanExtra(app.bloqueador.appupdate.AppUpdateWorker.EXTRA_OPEN_UPDATES, false) == true
    }
    var settingsSection by rememberSaveable {
        mutableStateOf(if (fromUpdate) SettingsSection.APP_UPDATES.name else null)
    }
    val fromThreat = remember {
        (context as? android.app.Activity)?.intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_THREATS, false) == true
    }
    var screen by rememberSaveable {
        mutableStateOf(
            when {
                !settings.onboardingDone -> Screen.ONBOARDING
                fromUpdate -> Screen.SETTINGS
                fromThreat -> Screen.HISTORY
                else -> Screen.HOME
            }
        )
    }
    // Estrategia que se está editando desde Ajustes (aún sin guardar).
    var pending by rememberSaveable { mutableStateOf(settings.strategy) }

    when (screen) {
        Screen.ONBOARDING -> OnboardingScreen(onDone = { screen = Screen.HOME })

        Screen.HOME -> HomeScreen(
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenHistory = { screen = Screen.HISTORY },
        )

        Screen.HISTORY -> {
            BackHandler { screen = Screen.HOME }
            HistoryScreen(onBack = { screen = Screen.HOME }, startWithThreats = fromThreat)
        }

        Screen.SETTINGS -> {
            fun leave() {
                settingsSection = null
                screen = Screen.HOME
            }
            BackHandler { leave() }
            SettingsScreen(
                section = settingsSection?.let { SettingsSection.valueOf(it) },
                onOpenSection = { settingsSection = it?.name },
                onBack = { leave() },
                onChangeStrategy = {
                    pending = settings.strategy
                    screen = Screen.STRATEGY
                },
                onEditApps = {
                    pending = settings.strategy
                    screen = Screen.APPS
                },
            )
        }

        Screen.STRATEGY -> {
            BackHandler { screen = Screen.SETTINGS }
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ScreenHeader("¿Qué apps quieres proteger?") { screen = Screen.SETTINGS }
                StrategyChooser(pending) { pending = it }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (pending == AppStrategy.ALL) {
                            applyAppSelection(context, AppStrategy.ALL, null)
                            screen = Screen.SETTINGS
                        } else {
                            screen = Screen.APPS
                        }
                    },
                ) { Text("Continuar") }
            }
        }

        Screen.APPS -> {
            BackHandler { screen = Screen.SETTINGS }
            val excluding = pending == AppStrategy.ALL_EXCEPT
            AppPickerScreen(
                strategy = pending,
                initial = if (excluding) settings.excludedApps else settings.includedApps,
                // Solo al estrenar "todas excepto": se proponen las apps de banca y pagos.
                preselectSensitive = excluding && settings.strategy != AppStrategy.ALL_EXCEPT,
                onBack = { screen = Screen.SETTINGS },
                onSave = { selection ->
                    applyAppSelection(context, pending, selection)
                    screen = Screen.SETTINGS
                },
            )
        }
    }
}
