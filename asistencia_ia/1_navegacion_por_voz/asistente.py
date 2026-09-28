import subprocess
import sys

from lector_pantalla import (
    ADB,
    adb_comando,
    obtener_interfaz_con_reintento,
    parsear_interfaz,
    priorizar_elementos,
)
from oido import escuchar_orden
from voz import hablar

if sys.stdout.encoding != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")


def normalizar(texto):
    return texto.lower().strip()


def buscar_boton_por_orden(orden, tocables):
    """La orden dicha ('abrí la billetera') no coincide letra por letra
    con el nombre del botón ('Billetera'). Buscamos el botón cuyo nombre
    aparezca mencionado dentro de la orden."""
    orden_normalizada = normalizar(orden)
    mejor_candidato = None
    mejor_longitud = 0

    for boton in tocables:
        nombre_normalizado = normalizar(boton["nombre"])
        if len(nombre_normalizado) < 3:
            continue
        if nombre_normalizado in orden_normalizada:
            if len(nombre_normalizado) > mejor_longitud:
                mejor_candidato = boton
                mejor_longitud = len(nombre_normalizado)

    return mejor_candidato


def ejecutar_asistente():
    orden = escuchar_orden()
    if not orden:
        hablar("No entendí ninguna orden.")
        print("No se reconoció ninguna orden.")
        return

    print(f"Orden reconocida: '{orden}'")

    print("Mirando la pantalla del celular...")
    obtener_interfaz_con_reintento()
    elementos = parsear_interfaz("window_dump.xml")
    tocables, _ = priorizar_elementos(elementos)

    boton = buscar_boton_por_orden(orden, tocables)

    if boton is None:
        mensaje = f"Dijiste '{orden}', pero no encontré ningún botón que coincida en esta pantalla."
        print(mensaje)
        hablar(mensaje)
        return

    mensaje = f"Tocando {boton['nombre']}."
    print(mensaje)
    hablar(mensaje)
    adb_comando(["shell", "input", "tap", str(boton["x"]), str(boton["y"])])


if __name__ == "__main__":
    ejecutar_asistente()
