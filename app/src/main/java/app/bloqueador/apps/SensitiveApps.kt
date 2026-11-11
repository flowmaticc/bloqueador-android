package app.bloqueador.apps

/**
 * Lista curada propia de apps de banca y pagos. Algunas detectan una VPN activa y se niegan a
 * funcionar, así que se recomienda excluirlas del filtro. Se reconoce por paquete exacto, por
 * prefijo y por palabras clave en el nombre del paquete.
 */
object SensitiveApps {

    private val exact = setOf(
        // Pagos y carteras
        "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user",
        "com.samsung.android.spay", "com.samsung.android.samsungpay.gear",
        "com.paypal.android.p2pmobile", "com.revolut.revolut", "de.number26.android",
        "com.transferwise.android", "com.bizum.app", "com.klarna.android",
        "com.mercadopago.wallet", "com.venmo", "com.squareup.cash",
        // Banca en España
        "com.bbva.bbvacontigo", "com.bbva.netcash", "es.bancosantander.apps",
        "es.lacaixa.mobile.android.newwapicon", "com.imaginbank.app", "es.bancosabadell.bsmovil",
        "com.bankinter.launcher", "es.unicajabanco.app", "com.kutxabank.android",
        "es.ibercaja.ibercajaapp", "com.abanca.bm", "es.openbank.mobile", "es.evobanco.bancamovil",
        "com.ing.banking", "www.ingdirect.nativeframe", "com.rsi", "es.cm.android",
        "es.pibank.customers", "app.wizink.es", "com.myinvestor",
        // Administración e identidad
        "es.gob.afirma", "es.aeat.pin24h", "es.gob.clave", "com.tgss.importass",
        // Inversión y cripto
        "com.coinbase.android", "com.binance.dev", "com.kraken.invest.app", "piuk.blockchain.android",
        "de.traderepublic.app", "com.degiro.android", "com.etoro.openbook",
    )

    private val prefixes = listOf(
        "com.bbva.", "es.bancosantander.", "com.santander.", "es.lacaixa.", "es.caixabank.",
        "com.caixabank.", "es.bancosabadell.", "com.bankinter.", "com.ing.", "es.gob.", "es.aeat.",
        "com.paypal.",
    )

    private val keywords = listOf(
        "bank", "banco", "banca", "banque", "caixa", "wallet", "bizum", "payment", "trading",
    )

    /** Paquetes que contienen una palabra clave sin ser banca. */
    private val keywordExceptions = listOf("powerbank", "piggybank", "foodbank", "databank")

    fun isSensitive(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        if (pkg in exact) return true
        if (prefixes.any { pkg.startsWith(it) }) return true
        if (keywordExceptions.any { it in pkg }) return false
        return keywords.any { it in pkg }
    }
}
