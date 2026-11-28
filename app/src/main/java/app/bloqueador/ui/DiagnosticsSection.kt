package app.bloqueador.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.bloqueador.BloqueadorApp
import app.bloqueador.R
import app.bloqueador.VpnState
import app.bloqueador.vpn.DiagnosticsInfo
import app.bloqueador.vpn.NetworkKind
import app.bloqueador.vpn.ResolverKind
import app.bloqueador.vpn.SelfTestResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Dominio normal de la prueba en vivo: tiene que resolverse. */
private const val TEST_NORMAL = "wikipedia.org"

/** Dominios de publicidad de prueba: se usa el primero que esté en las listas activas. */
private val TEST_ADS = listOf("doubleclick.net", "googleadservices.com", "ads.google.com", "adservice.google.com")

/**
 * Diagnóstico: no basta con que la VPN esté encendida. Muestra si el filtro atiende consultas, qué
 * listas tiene, quién resuelve, por qué red sale, qué puede quedar fuera, y una prueba en vivo que
 * pasa por el filtro igual que una app. Todo es local: no se envía nada.
 */
@Composable
internal fun DiagnosticsSection() {
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val numbers = remember { NumberFormat.getIntegerInstance() }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick // se recompone cada segundo
    val running = app.vpnState.value is VpnState.Running
    val info: DiagnosticsInfo? = if (running) runCatching { app.diagnostics?.invoke() }.getOrNull() else null
    fun ago(ms: Long) = relativeTime(context, ms)

    SectionCard(stringResource(R.string.diag_filter)) {
        Text(stringResource(if (running) R.string.diag_tunnel_on else R.string.diag_tunnel_off))
        val last = app.engine.lastQueryAt
        if (running) {
            Text(
                if (last == 0L) stringResource(R.string.diag_no_queries)
                else stringResource(R.string.diag_queries, numbers.format(app.engine.queries.get()), ago(last)),
            )
        }
    }

    SectionCard(stringResource(R.string.diag_lists)) {
        Text(stringResource(R.string.diag_rules, numbers.format(app.engine.matcher.size), numbers.format(app.engine.threats.size)))
        val updated = app.repository.lastUpdated
        Text(stringResource(R.string.diag_updated, if (updated > 0) ago(updated) else stringResource(R.string.diag_never)))
        app.repository.lastError?.let {
            Text(stringResource(R.string.diag_last_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (info != null) {
        SectionCard(stringResource(R.string.diag_dns)) {
            Text(
                when (info.resolver) {
                    ResolverKind.ENCRYPTED -> stringResource(R.string.diag_resolver_encrypted, info.encryptedProvider.orEmpty())
                    ResolverKind.NETWORK -> stringResource(R.string.diag_resolver_network)
                    ResolverKind.NETWORK_UNVALIDATED -> stringResource(R.string.diag_resolver_unvalidated)
                },
            )
            Text(
                if (info.lastAnswerAt == 0L) stringResource(R.string.diag_no_answer)
                else stringResource(R.string.diag_last_answer, ago(info.lastAnswerAt)),
            )
        }

        SectionCard(stringResource(R.string.diag_network)) {
            Text(
                stringResource(
                    when (info.network) {
                        NetworkKind.WIFI -> R.string.diag_net_wifi
                        NetworkKind.CELLULAR -> R.string.diag_net_cellular
                        NetworkKind.ETHERNET -> R.string.diag_net_ethernet
                        NetworkKind.OTHER -> R.string.diag_net_other
                        null -> R.string.diag_net_none
                    },
                ),
            )
            if (info.networkDns.isNotEmpty()) {
                Text(stringResource(R.string.diag_net_dns, info.networkDns.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    SectionCard(stringResource(R.string.diag_coverage)) {
        val fixedDns = privateDnsFixedHost(context)
        Text(if (fixedDns != null) stringResource(R.string.diag_private_dns_fixed, fixedDns) else stringResource(R.string.diag_private_dns_ok))
        Text(stringResource(if (app.settings.vpnLockdown) R.string.diag_lockdown_on else R.string.diag_lockdown_off))
        Text(stringResource(R.string.diag_apps, strategySummary(context, app.settings)))
        if (info != null) Text(stringResource(if (info.publicDnsCaptured) R.string.diag_public_on else R.string.diag_public_off))
    }

    SelfTestCard(running)
}

@Composable
private fun SelfTestCard(running: Boolean) {
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }

    SectionCard(stringResource(R.string.diag_test)) {
        Text(stringResource(R.string.diag_test_text), style = MaterialTheme.typography.bodySmall)
        if (!running) {
            Text(stringResource(R.string.diag_test_off))
        } else {
            Button(enabled = !testing, onClick = {
                testing = true
                lines = emptyList()
                scope.launch {
                    val test = app.selfTest
                    val out = mutableListOf<String>()
                    if (test == null) {
                        out += context.getString(R.string.diag_test_off)
                    } else {
                        out += describe(context, TEST_NORMAL, test(TEST_NORMAL), expectBlocked = false)
                        val ad = TEST_ADS.firstOrNull { app.engine.peek(it) != null }
                        out += if (ad == null) context.getString(R.string.diag_test_no_blocked_domain)
                        else describe(context, ad, test(ad), expectBlocked = true)
                    }
                    lines = out
                    testing = false
                }
            }) { Text(stringResource(if (testing) R.string.diag_testing else R.string.diag_test_run)) }
        }
        lines.forEach { Text(it) }
    }
}

private fun describe(context: Context, domain: String, r: SelfTestResult, expectBlocked: Boolean): String = when (r) {
    is SelfTestResult.Resolved ->
        if (expectBlocked) context.getString(R.string.diag_test_not_blocked, domain, r.address)
        else context.getString(R.string.diag_test_normal_ok, domain, r.address, r.millis.toInt())
    is SelfTestResult.Blocked ->
        if (expectBlocked) context.getString(R.string.diag_test_blocked_ok, domain, r.millis.toInt())
        else context.getString(R.string.diag_test_unexpected_block, domain)
    is SelfTestResult.Failed -> context.getString(R.string.diag_test_failed, domain, r.rcode)
    SelfTestResult.Timeout -> context.getString(R.string.diag_test_timeout, domain)
    SelfTestResult.NotRunning -> context.getString(R.string.diag_test_off)
}

/** Proveedor de DNS privado fijo de Android (p. ej. "dns.google"), o null si es automático o está desactivado. */
private fun privateDnsFixedHost(context: Context): String? {
    if (Build.VERSION.SDK_INT < 28) return null
    val r = context.contentResolver
    if (Settings.Global.getString(r, "private_dns_mode") != "hostname") return null
    return Settings.Global.getString(r, "private_dns_specifier")?.takeIf { it.isNotBlank() } ?: "?"
}
