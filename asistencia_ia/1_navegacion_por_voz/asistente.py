import os
import sys

import requests

from lector_pantalla import (
    ADB,
    adb_comando,
    obtener_interfaz_con_reintento,
    parsear_interfaz,
    priorizar_elementos,
)
from oido import escuchar_orden
from voz import hablar

# Cerebro en Docker (docker compose up -d). Si no responde, se usa la búsqueda local.
CEREBRO_URL = os.environ.get("CEREBRO_URL", "http://127.0.0.1:8000")
TENANT_ID = os.environ.get("TENANT_ID", "demo")

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


def preguntar_al_cerebro(orden, tocables):
    """Devuelve (boton o None, frase para decir), o None si el cerebro no responde."""
    try:
        r = requests.post(f"{CEREBRO_URL}/interpretar", json={
            "tenant_id": TENANT_ID,
            "orden": orden,
            "botones": [{"nombre": b["nombre"], "x": b["x"], "y": b["y"]} for b in tocables],
        }, timeout=60)
        r.raise_for_status()
        datos = r.json()
    except requests.RequestException:
        return None
    if datos["accion"] == "tocar":
        return {"nombre": datos["nombre"], "x": datos["x"], "y": datos["y"]}, datos["respuesta"]
    return None, datos["respuesta"]


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

    del_cerebro = preguntar_al_cerebro(orden, tocables)
    if del_cerebro is None:
        print("(Cerebro no disponible, uso búsqueda local)")
        boton = buscar_boton_por_orden(orden, tocables)
        mensaje = None
    else:
        boton, mensaje = del_cerebro

    if boton is None:
        mensaje = mensaje or f"Dijiste '{orden}', pero no encontré ningún botón que coincida en esta pantalla."
        print(mensaje)
        hablar(mensaje)
        return

    mensaje = mensaje or f"Tocando {boton['nombre']}."
    print(mensaje)
    hablar(mensaje)
    adb_comando(["shell", "input", "tap", str(boton["x"]), str(boton["y"])])


if __name__ == "__main__":
    ejecutar_asistente()
