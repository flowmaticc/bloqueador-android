package app.bloqueador.loadtest

/**
 * Webs reales que la prueba de carga abre en un navegador interno. Variadas a propósito: medios
 * generalistas y deportivos (mucha publicidad programática), tecnología, recetas, tiempo, ocio,
 * clasificados, herramientas, referencia e internacionales. Solo webs legítimas y conocidas.
 */
object Sites {
    data class Site(val url: String, val category: String)

    val all: List<Site> = listOf(
        // Medios generalistas (España)
        "https://elpais.com/" to "medios", "https://www.elmundo.es/" to "medios", "https://www.abc.es/" to "medios",
        "https://www.lavanguardia.com/" to "medios", "https://www.20minutos.es/" to "medios",
        "https://www.elconfidencial.com/" to "medios", "https://www.eldiario.es/" to "medios",
        "https://www.larazon.es/" to "medios", "https://www.publico.es/" to "medios", "https://www.elespanol.com/" to "medios",
        "https://okdiario.com/" to "medios", "https://www.huffingtonpost.es/" to "medios",
        "https://www.europapress.es/" to "medios", "https://www.elperiodico.com/es/" to "medios",
        "https://www.lasprovincias.es/" to "medios", "https://www.levante-emv.com/" to "medios",
        "https://www.heraldo.es/" to "medios", "https://www.diariodesevilla.es/" to "medios",
        "https://www.lavozdeasturias.es/" to "medios", "https://www.elcorreo.com/" to "medios",
        // Deportes
        "https://www.marca.com/" to "deportes", "https://as.com/" to "deportes",
        "https://www.mundodeportivo.com/" to "deportes", "https://www.sport.es/es/" to "deportes",
        "https://www.relevo.com/" to "deportes", "https://www.estadiodeportivo.com/" to "deportes",
        // Tecnología
        "https://www.xataka.com/" to "tecnologia", "https://www.genbeta.com/" to "tecnologia",
        "https://www.applesfera.com/" to "tecnologia", "https://hipertextual.com/" to "tecnologia",
        "https://computerhoy.20minutos.es/" to "tecnologia", "https://andro4all.com/" to "tecnologia",
        // Recetas y hogar
        "https://www.directoalpaladar.com/" to "recetas", "https://www.recetasgratis.net/" to "recetas",
        "https://www.hogarmania.com/" to "recetas", "https://www.allrecipes.com/" to "recetas",
        // Tiempo
        "https://www.eltiempo.es/" to "tiempo", "https://www.tiempo.com/" to "tiempo",
        "https://weather.com/es-ES/" to "tiempo", "https://www.accuweather.com/es/" to "tiempo",
        // Ocio, corazón y moda
        "https://www.hola.com/" to "ocio", "https://www.lecturas.com/" to "ocio", "https://www.diezminutos.es/" to "ocio",
        "https://www.sensacine.com/" to "ocio", "https://www.elle.com/es/" to "ocio", "https://www.cosmopolitan.com/es/" to "ocio",
        "https://www.vogue.es/" to "ocio", "https://www.fotogramas.es/" to "ocio",
        // Clasificados y compras
        "https://www.milanuncios.com/" to "compras", "https://www.idealista.com/" to "compras",
        "https://www.fotocasa.es/" to "compras", "https://www.coches.net/" to "compras", "https://www.amazon.es/" to "compras",
        // Herramientas y referencia
        "https://www.speedtest.net/" to "herramientas", "https://www.wordreference.com/es/" to "herramientas",
        "https://www.timeanddate.com/" to "herramientas", "https://www.wikihow.com/" to "herramientas",
        "https://www.letras.com/" to "herramientas", "https://www.musica.com/" to "herramientas",
        "https://www.fandom.com/" to "herramientas",
        // Internacionales
        "https://www.dailymail.co.uk/" to "internacional", "https://nypost.com/" to "internacional",
        "https://edition.cnn.com/" to "internacional", "https://www.foxnews.com/" to "internacional",
        "https://www.theguardian.com/" to "internacional", "https://www.forbes.com/" to "internacional",
        "https://www.buzzfeed.com/" to "internacional", "https://www.msn.com/es-es" to "internacional",
        "https://www.yahoo.com/" to "internacional", "https://www.independent.co.uk/" to "internacional",
        "https://www.mirror.co.uk/" to "internacional", "https://www.dailymotion.com/" to "internacional",
    ).map { (url, cat) -> Site(url, cat) }
}
