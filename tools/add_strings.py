"""
Añade textos a res/values/strings.xml (inglés) y res/values-es/strings.xml (español) a la vez.

Uso: py -3 tools/add_strings.py textos.json
textos.json: lista de entradas
  ["clave", "texto en inglés", "texto en español"]
  ["clave", {"one": "...", "other": "..."}, {"one": "...", "other": "..."}]   (plurales)
Escapa apóstrofos, comillas y los caracteres especiales de Android. Falla si una clave ya existe.
"""
import json
import re
import sys
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
FILES = {"en": RES / "values/strings.xml", "es": RES / "values-es/strings.xml"}


def esc(text: str) -> str:
    t = text.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = t.replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if t[:1] in ("@", "?"):
        t = "\\" + t
    return t


def entry(key: str, value) -> str:
    if isinstance(value, dict):
        items = "".join(f'\n        <item quantity="{q}">{esc(v)}</item>' for q, v in value.items())
        return f'    <plurals name="{key}">{items}\n    </plurals>\n'
    # Más de un %: hay que numerarlos (%1$s, %2$d) para que los idiomas puedan reordenarlos.
    if len(re.findall(r"%(?!%)", value)) > 1 and re.search(r"%[sd]", value):
        sys.exit(f"{key}: con varios valores usa %1$s, %2$d…")
    return f'    <string name="{key}">{esc(value)}</string>\n'


def main() -> None:
    data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    docs = {lang: f.read_text(encoding="utf-8") for lang, f in FILES.items()}
    for key, en, es in data:
        for lang, doc in docs.items():
            if f'name="{key}"' in doc:
                sys.exit(f"La clave {key} ya existe en {lang}")
        docs["en"] = docs["en"].replace("</resources>", entry(key, en) + "</resources>")
        docs["es"] = docs["es"].replace("</resources>", entry(key, es) + "</resources>")
    for lang, f in FILES.items():
        f.write_text(docs[lang], encoding="utf-8", newline="\n")
    print(f"{len(data)} textos añadidos")


if __name__ == "__main__":
    main()
