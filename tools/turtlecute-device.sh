#!/usr/bin/env bash
# Test de dominios de https://adblock.turtlecute.org ejecutado DENTRO del dispositivo, a través del
# filtro real (VPN activa): resuelve cada uno de sus dominios con el DNS del sistema y cuenta los que
# vuelven bloqueados (0.0.0.0). Da el porcentaje por sección y la lista de los que pasan.
#
# Es la misma parte "Host" que mide la web; las partes cosmética y de scripts no aplican a un filtro
# DNS. Para comparar con las listas sin dispositivo: TurtlecuteCoverageTest (ver docs/PRUEBAS.md).
#
# Uso: tools/turtlecute-device.sh <serial-adb> [carpeta-de-salida]
#   (el filtro debe estar activado en el dispositivo; el PC necesita red)
set -u
export MSYS_NO_PATHCONV=1
SERIAL="$1"
OUT="${2:-}"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
URL="https://adblock.turtlecute.org/d3host.txt"
a() { "$ADB" -s "$SERIAL" "$@"; }

if [ "$(a shell ip addr show tun0 2>/dev/null | grep -c '10.111.222.1')" -eq 0 ]; then
  echo "  AVISO turtlecute: el filtro no está activo en $SERIAL; no se prueba"; exit 2
fi

# sección<TAB>dominio, en el orden de la lista
LIST=$(curl -fsSL "$URL" | awk '
  /^#=+/ { s=$0; gsub(/^#=+ *| *=+ *$/, "", s); next }
  /^#/ || NF < 2 { next }
  { print s "\t" tolower($2) }')
[ -z "$LIST" ] && { echo "  AVISO turtlecute: no se pudo descargar $URL"; exit 2; }
DOMAINS=$(echo "$LIST" | cut -f2 | tr '\n' ' ')

# Todas las consultas en paralelo dentro del dispositivo; ping solo sirve para forzar la resolución.
RES=$(a shell "for d in $DOMAINS; do (r=\$(ping -c1 -W1 \$d 2>&1 | head -1); echo \"\$d|\$r\") & done; wait" | tr -d '\r')

echo "$LIST" | awk -F'\t' -v res="$RES" '
  BEGIN { n = split(res, L, "\n"); for (i = 1; i <= n; i++) { p = index(L[i], "|"); R[substr(L[i], 1, p-1)] = substr(L[i], p+1) } }
  {
    sec = $1; d = $2; tot[sec]++; all++
    if (!(sec in seen)) { seen[sec] = 1; order[++k] = sec }
    r = R[d]
    if (r ~ /\((0\.0\.0\.0|127\.0\.0\.1)\)/) { blk[sec]++; b++ }
    else missed = missed sprintf("      %-16s %s  →  %s\n", sec, d, (r == "" ? "sin respuesta" : r))
  }
  END {
    printf "  Turtlecute (dominios, en el dispositivo): %d %% (%d/%d)\n", b*100/all, b, all
    for (i = 1; i <= k; i++) { s = order[i]; printf "      %-16s %3d %% (%d/%d)\n", s, blk[s]*100/tot[s], blk[s], tot[s] }
    if (missed != "") printf "    No bloqueados:\n%s", missed
  }' | tee ${OUT:+"$OUT/turtlecute.txt"}
