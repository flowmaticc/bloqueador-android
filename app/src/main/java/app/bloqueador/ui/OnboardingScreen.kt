package app.bloqueador.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bloqueador.BloqueadorApp
import app.bloqueador.apps.AppStrategy

/** Primer arranque: explicación del funcionamiento → qué apps proteger → (selector de apps). */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val settings = BloqueadorApp.instance.settings
    var step by rememberSaveable { mutableIntStateOf(0) }
    var strategy by rememberSaveable { mutableStateOf(AppStrategy.ALL) }

    fun finish(selection: Set<String>?) {
        if (selection != null) {
            if (strategy == AppStrategy.ALL_EXCEPT) settings.excludedApps = selection
            if (strategy == AppStrategy.ONLY) settings.includedApps = selection
        }
        settings.strategy = strategy
        settings.onboardingDone = true
        onDone()
    }

    BackHandler(enabled = step > 0) { step-- }

    when (step) {
        0 -> OnboardingPage(
            title = "Bienvenido a Garbillo",
            button = "Continuar",
            onNext = { step = 1 },
        ) {
            Bullet(
                "Bloquea publicidad y rastreadores en todo el móvil",
                "Funciona en el navegador y dentro de las apps, sin root.",
            )
            Bullet(
                "Usa una VPN local, no un servidor externo",
                "La \"VPN\" vive dentro del móvil. Tu tráfico no se envía a ningún sitio nuevo y la app no recoge ningún dato.",
            )
            Bullet(
                "Solo mira los nombres de dominio",
                "Comprueba a qué dirección quiere conectarse cada app. No puede ver el contenido de lo que haces.",
            )
            Bullet(
                "Lo que no puede quitar",
                LIMITS_TEXT,
            )
            Bullet(
                "Verás una llave y una notificación fija",
                "Android las muestra siempre que hay una VPN activa. Es normal y no gasta batería.",
            )
            Bullet(
                "Android te pedirá permiso",
                "La primera vez que actives el filtro saldrá un aviso de \"Solicitud de conexión\".",
            )
        }

        1 -> OnboardingPage(
            title = "¿Qué apps quieres proteger?",
            button = "Continuar",
            onNext = { if (strategy == AppStrategy.ALL) finish(null) else step = 2 },
        ) {
            StrategyChooser(strategy) { strategy = it }
            Text(
                "Podrás cambiarlo cuando quieras en Ajustes → Apps afectadas.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        else -> AppPickerScreen(
            strategy = strategy,
            initial = emptySet(),
            preselectSensitive = strategy == AppStrategy.ALL_EXCEPT,
            onBack = { step = 1 },
            onSave = { finish(it) },
        )
    }
}

@Composable
private fun OnboardingPage(
    title: String,
    button: String,
    onNext: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.padding(top = 8.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            content()
        }
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(button) }
    }
}

@Composable
private fun Bullet(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Límites de un bloqueador por DNS, dichos con claridad. */
internal const val LIMITS_TEXT =
    "Los anuncios de YouTube y Twitch y las publicaciones promocionadas de redes sociales llegan por " +
        "los mismos servidores que el contenido, así que ningún bloqueador de este tipo puede quitarlos. " +
        "Dentro de las webs puede quedar el hueco del anuncio: para eso, usa Brave o Firefox con " +
        "uBlock Origin; se complementan con Garbillo."
