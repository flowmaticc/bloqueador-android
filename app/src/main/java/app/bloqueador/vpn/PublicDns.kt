package app.bloqueador.vpn

/**
 * Servidores DNS públicos conocidos que algunas apps usan directamente, saltándose el DNS del sistema
 * (y por tanto el filtro). Se enrutan por el túnel:
 * - Las consultas DNS normales (UDP, puerto 53) hacia ellos se filtran como cualquier otra y se
 *   reenvían al DNS de la red; la respuesta sale con la dirección del servidor al que se preguntó.
 * - Cualquier conexión TCP (DNS por TCP, DNS sobre TLS en el 853, DNS sobre HTTPS en el 443) recibe un
 *   RST al momento: falla enseguida y la app (Chrome, Firefox…) vuelve al DNS del sistema, que sí se
 *   filtra.
 *
 * Solo direcciones de servicios de DNS: no es una ruta para todo el tráfico. Garbillo está fuera de
 * su propio túnel, así que su DNS cifrado (Quad9, Cloudflare, Mullvad) no se ve afectado.
 */
internal object PublicDns {
    val V4 = listOf(
        // Google
        "8.8.8.8", "8.8.4.4",
        // Cloudflare (normal, malware, familia)
        "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3",
        // Quad9
        "9.9.9.9", "149.112.112.112", "9.9.9.10", "149.112.112.10", "9.9.9.11", "149.112.112.11",
        // OpenDNS / Cisco Umbrella
        "208.67.222.222", "208.67.220.220", "208.67.222.123", "208.67.220.123",
        // AdGuard
        "94.140.14.14", "94.140.15.15", "94.140.14.15", "94.140.15.16", "94.140.14.140", "94.140.14.141",
        // CleanBrowsing
        "185.228.168.9", "185.228.169.9", "185.228.168.168", "185.228.169.168",
        // Control D
        "76.76.2.0", "76.76.10.0",
        // Mullvad
        "194.242.2.2", "194.242.2.3", "194.242.2.4",
    )

    val V6 = listOf(
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2606:4700:4700::1111", "2606:4700:4700::1001", "2606:4700:4700::1112", "2606:4700:4700::1002",
        "2606:4700:4700::1113", "2606:4700:4700::1003",
        "2620:fe::fe", "2620:fe::9", "2620:fe::10", "2620:fe::fe:10", "2620:fe::11", "2620:fe::fe:11",
        "2620:119:35::35", "2620:119:53::53",
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff", "2a10:50c0::1:ff", "2a10:50c0::2:ff",
        "2a0d:2a00:1::", "2a0d:2a00:2::",
        "2606:1a40::", "2606:1a40:1::",
        "2a07:e340::2", "2a07:e340::3", "2a07:e340::4",
    )
}
