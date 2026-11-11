#!/usr/bin/env bash
# Prueba de humo en un emulador o dispositivo: instala, completa la bienvenida, activa el filtro,
# comprueba bloqueo / resolución normal / historial / servicio en primer plano, y lo apaga.
#
# Uso: tools/smoke-test.sh <serial-adb> <carpeta-de-salida>
#   (el emulador debe estar arrancado; el APK de depuración ya compilado)
set -u
# En Git Bash (Windows), evita que rutas como /sdcard/... se conviertan en rutas de Windows.
export MSYS_NO_PATHCONV=1
SERIAL="$1"
OUT="$2"
mkdir -p "$OUT"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="app.bloqueador"
a() { "$ADB" -s "$SERIAL" "$@"; }
PASS=0; FAIL=0
ok()   { echo "  OK    $*"; PASS=$((PASS+1)); }
bad()  { echo "  FALLO $*"; FAIL=$((FAIL+1)); }

# Pulsa el centro del primer nodo de la pantalla cuyo atributo coincide (text, resource-id…).
tap_node() {
  local attr="$1" value="$2" bounds="" try
  # uiautomator a veces falla si la pantalla aún se está animando: se reintenta.
  for try in 1 2 3; do
    a shell rm -f /sdcard/ui.xml
    a shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    bounds=$(a exec-out cat /sdcard/ui.xml 2>/dev/null | grep -o "<node [^>]*$attr=\"$value\"[^>]*>" | head -1 | grep -o 'bounds="[^"]*"')
    [ -n "$bounds" ] && break
    sleep 1
  done
  [ -z "$bounds" ] && return 1
  local n; n=$(echo "$bounds" | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $n
  a shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
# El interruptor de Compose aparece como nodo "checkable".
tap_switch() { tap_node checkable true; }

SDK=$(a shell getprop ro.build.version.sdk | tr -d '\r')
echo "== Dispositivo $SERIAL · API $SDK"

a uninstall "$PKG" >/dev/null 2>&1
a install -r -g "$APK" | grep -q Success && ok "instalación" || bad "instalación"
a shell input keyevent KEYCODE_WAKEUP; a shell wm dismiss-keyguard >/dev/null 2>&1
a shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null
sleep 3
a exec-out screencap -p > "$OUT/1-bienvenida.png"
tap_node text "Continuar" && sleep 1.5 && tap_node text "Continuar" && sleep 2 \
  && ok "bienvenida completada" || bad "bienvenida"

tap_switch || bad "no encuentro el interruptor"
# Avisos del sistema: permiso de notificaciones (API 33+) y consentimiento VPN.
for _ in 1 2 3 4; do
  sleep 2
  tap_node resource-id "com.android.permissioncontroller:id/permission_allow_button" \
    || tap_node resource-id "android:id/button1" || true
done
sleep 12
a exec-out screencap -p > "$OUT/2-protegido.png"

VPN=$(a shell ip addr show tun0 2>/dev/null | grep -c '10.111.222.1')
[ "$VPN" -ge 1 ] && ok "VPN activa" || bad "VPN no activa"
a shell dumpsys activity services "$PKG" | grep -q "isForeground=true" && ok "servicio en primer plano" \
  || bad "servicio no está en primer plano"

BLOCK=$(a shell "ping -c1 -W3 leadbolt.com 2>&1 | head -1")
echo "$BLOCK" | grep -qE "\((127\.0\.0\.1|0\.0\.0\.0)\)" && ok "bloquea leadbolt.com" || bad "no bloquea: $BLOCK"
ALLOW=$(a shell "ping -c1 -W3 wikipedia.org 2>&1 | head -1")
echo "$ALLOW" | grep -qE "\((127\.0\.0\.1|0\.0\.0\.0)\)|unknown" && bad "no resuelve wikipedia.org: $ALLOW" \
  || ok "resuelve wikipedia.org"

# Historial: espera la escritura por lotes y copia la base de datos para leerla.
sleep 7
for f in history.db history.db-wal history.db-shm; do
  a exec-out run-as "$PKG" cat "databases/$f" > "$OUT/$f" 2>/dev/null
done
python - "$(cygpath -w "$OUT/history.db")" "$SDK" <<'EOF' && ok "historial guardado" || bad "historial vacío"
import sqlite3, sys
db = sqlite3.connect(sys.argv[1]); sdk = int(sys.argv[2])
rows = db.execute("select domain, appPackage, blocked from dns_events").fetchall()
lead = [r for r in rows if r[0] == "leadbolt.com" and r[2] == 1]
print("    entradas:", len(rows), "· leadbolt:", lead[:1])
if not lead: sys.exit(1)
if sdk >= 29 and lead[0][1] != "com.android.shell":
    print("    (aviso) app esperada com.android.shell, obtenida", lead[0][1])
EOF

tap_node text "Ver historial" && sleep 2 && a exec-out screencap -p > "$OUT/3-historial.png"
a shell input keyevent KEYCODE_BACK; sleep 1

tap_switch; sleep 3
VPN=$(a shell ip addr show tun0 2>/dev/null | grep -c '10.111.222.1')
[ "$VPN" -eq 0 ] && ok "VPN se apaga" || bad "VPN sigue activa"

echo "== Resultado API $SDK: $PASS correctas, $FAIL fallos"
