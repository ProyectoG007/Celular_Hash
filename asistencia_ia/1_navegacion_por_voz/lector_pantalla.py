import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from voz import hablar

if sys.stdout.encoding != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")

ADB = "../../platform-tools/adb.exe"


def adb_comando(args_lista):
    resultado = subprocess.run([ADB] + args_lista, capture_output=True, text=True)
    return resultado.stdout + resultado.stderr


def obtener_interfaz_con_reintento(intentos=4, espera_seg=1.5):
    """Si la pantalla está en transición o con video reproduciéndose,
    reintenta en vez de devolver una lectura vacía o inválida."""
    for intento in range(1, intentos + 1):
        salida = adb_comando(["shell", "uiautomator", "dump", "//sdcard/window_dump.xml"])
        if "ERROR" not in salida and "could not get idle state" not in salida:
            adb_comando(["pull", "//sdcard/window_dump.xml", "window_dump.xml"])
            return True
        time.sleep(espera_seg)
    adb_comando(["pull", "//sdcard/window_dump.xml", "window_dump.xml"])
    return False


def parsear_interfaz(archivo_xml):
    arbol = ET.parse(archivo_xml)
    raiz = arbol.getroot()
    elementos = []
    for nodo in raiz.iter('node'):
        texto = nodo.attrib.get('text', '')
        desc = nodo.attrib.get('content-desc', '')
        limites = nodo.attrib.get('bounds', '')
        clickable = nodo.attrib.get('clickable', '') == 'true'
        if (texto or desc) and limites:
            coords = limites.replace('][', ',').replace('[', '').replace(']', '').split(',')
            x_centro = (int(coords[0]) + int(coords[2])) // 2
            y_centro = (int(coords[1]) + int(coords[3])) // 2
            nombre = texto if texto else desc
            elementos.append({
                "nombre": nombre,
                "x": x_centro,
                "y": y_centro,
                "clickable": clickable,
            })
    return elementos


PATRONES_RUIDO = re.compile(
    r"^(lun|mar|mi[eé]|jue|vie|s[aá]b|dom)"  # días de la semana abreviados
    r"|^\d{1,2}\n?\d{2}$"                     # relojes tipo "10\n00"
    r"|soleado|nublado|lluvi|tormenta|nevad",  # estados del clima
    re.IGNORECASE,
)


def es_ruido_de_widget(nombre):
    return bool(PATRONES_RUIDO.search(nombre))


def priorizar_elementos(elementos):
    """TalkBack lee todo en orden de arriba a abajo, sin importar
    qué tan relevante es. Acá priorizamos lo que un usuario probablemente
    quiere: primero los botones tocables, después el resto."""
    utiles = [e for e in elementos if not es_ruido_de_widget(e["nombre"])]
    tocables = [e for e in utiles if e["clickable"]]
    solo_texto = [e for e in utiles if not e["clickable"]]
    return tocables, solo_texto


def describir_pantalla_en_voz_simple(elementos):
    """Convierte la lista técnica en una frase corta, como si se la
    dijeras a alguien que no está mirando la pantalla."""
    tocables, solo_texto = priorizar_elementos(elementos)

    if not tocables and not solo_texto:
        return "No detecto nada en esta pantalla. Puede estar cargando."

    frases = []
    if tocables:
        nombres = [e["nombre"] for e in tocables[:5]]
        frases.append(f"Hay {len(tocables)} botones. Los principales son: {', '.join(nombres)}.")
    if solo_texto:
        nombres_texto = [e["nombre"] for e in solo_texto[:3] if len(e["nombre"]) < 40]
        if nombres_texto:
            frases.append(f"También veo el texto: {', '.join(nombres_texto)}.")

    return " ".join(frases)


if __name__ == "__main__":
    print("Leyendo pantalla actual del celular...")
    ok = obtener_interfaz_con_reintento()
    if not ok:
        print("Aviso: la pantalla estaba ocupada, la lectura puede ser incompleta.")

    elementos = parsear_interfaz("window_dump.xml")
    descripcion = describir_pantalla_en_voz_simple(elementos)

    print("\n--- Lo que se le dice en voz al usuario ---")
    print(descripcion)
    hablar(descripcion)

    tocables, _ = priorizar_elementos(elementos)
    if tocables:
        print("\n--- Botones disponibles para tocar por número ---")
        for i, e in enumerate(tocables):
            print(f"[{i}] {e['nombre']}")
