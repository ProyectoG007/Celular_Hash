"""Cerebro Central: corre dentro de Docker.

El modelo (Ollama) solo traduce la orden a un JSON. Este código valida
ese JSON antes de devolverlo: si el modelo inventa un botón que no existe,
la respuesta se descarta y se usa la búsqueda simple por texto.
Cada cliente (tenant_id) tiene su propia colección en el RAG.
"""
import hmac
import json
import os
import re

import chromadb
import requests
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://ollama:11434")
MODELO_CHAT = os.environ.get("MODELO_CHAT", "qwen2.5:3b")
MODELO_EMBED = os.environ.get("MODELO_EMBED", "nomic-embed-text")
MODELO_VISION = os.environ.get("MODELO_VISION", "qwen2.5vl:3b")
RUTA_DATOS = os.environ.get("RUTA_DATOS", "/datos/chroma")
# "cliente1:clave1,cliente2:clave2". Vacío = sin claves (solo para uso local).
CLAVES = dict(par.split(":", 1) for par in os.environ.get("CLAVES", "").split(",") if ":" in par)

app = FastAPI(title="Celular_Hash - Cerebro Central")
_db = None


def db():
    global _db
    if _db is None:
        _db = chromadb.PersistentClient(
            path=RUTA_DATOS, settings=chromadb.Settings(anonymized_telemetry=False))
    return _db


def coleccion(tenant_id, clave):
    if not re.fullmatch(r"[a-z0-9_-]{3,40}", tenant_id):
        raise HTTPException(400, "tenant_id inválido: usar minúsculas, números, - o _")
    if CLAVES and (tenant_id not in CLAVES
                   or not hmac.compare_digest(CLAVES[tenant_id].encode(), (clave or "").encode())):
        raise HTTPException(401, "Cliente o clave incorrectos")
    return db().get_or_create_collection(f"tenant_{tenant_id}")


def embeber(textos):
    r = requests.post(f"{OLLAMA_URL}/api/embed",
                      json={"model": MODELO_EMBED, "input": textos}, timeout=120)
    r.raise_for_status()
    return r.json()["embeddings"]


class Boton(BaseModel):
    nombre: str
    x: int
    y: int


class PedidoInterpretar(BaseModel):
    tenant_id: str
    orden: str
    botones: list[Boton]


class PedidoFoto(BaseModel):
    tenant_id: str
    imagen_base64: str
    pregunta: str = ""


class PedidoCargar(BaseModel):
    textos: list[str]


class PedidoConsultar(BaseModel):
    pregunta: str
    cantidad: int = 3


def buscar_por_texto(orden, botones):
    """Misma lógica que asistente.py: el botón cuyo nombre aparece en la orden."""
    orden = orden.lower().strip()
    mejor, largo = None, 0
    for i, b in enumerate(botones):
        nombre = b.nombre.lower().strip()
        if len(nombre) >= 3 and nombre in orden and len(nombre) > largo:
            mejor, largo = i, len(nombre)
    return mejor


def preguntar_al_modelo(orden, botones, contexto):
    lista = "\n".join(f"{i}: {b.nombre}" for i, b in enumerate(botones))
    prompt = (
        "Sos el asistente de un celular Android. El usuario dio una orden por voz "
        "(puede tener errores de reconocimiento, ej. 'sap' puede ser 'WhatsApp').\n"
        f"Orden: {orden}\n\nBotones visibles en pantalla:\n{lista}\n\n"
        + (f"Datos del negocio:\n{contexto}\n\n" if contexto else "")
        + 'Respondé SOLO un JSON: {"indice": <número del botón o null>, '
        '"respuesta": "<frase corta para decir en voz alta>"}'
    )
    r = requests.post(f"{OLLAMA_URL}/api/chat", json={
        "model": MODELO_CHAT,
        "messages": [{"role": "user", "content": prompt}],
        "format": "json",
        "stream": False,
        "options": {"temperature": 0},
    }, timeout=120)
    r.raise_for_status()
    return json.loads(r.json()["message"]["content"])


@app.get("/salud")
def salud():
    try:
        requests.get(f"{OLLAMA_URL}/api/tags", timeout=5).raise_for_status()
        return {"cerebro": "ok", "ollama": "ok"}
    except requests.RequestException:
        return {"cerebro": "ok", "ollama": "sin conexión"}


@app.post("/interpretar")
def interpretar(p: PedidoInterpretar, x_clave: str = Header("")):
    """Devuelve qué botón tocar. Nunca devuelve un botón que no esté en la lista."""
    contexto = ""
    col = coleccion(p.tenant_id, x_clave)
    try:
        if col.count():
            res = col.query(query_embeddings=embeber([p.orden]), n_results=min(3, col.count()))
            contexto = "\n".join(res["documents"][0])
        salida = preguntar_al_modelo(p.orden, p.botones, contexto)
        indice = salida.get("indice")
        respuesta = str(salida.get("respuesta") or "")[:300]
        # Blindaje: el índice tiene que ser un entero dentro de la lista.
        if not (isinstance(indice, int) and not isinstance(indice, bool)
                and 0 <= indice < len(p.botones)):
            indice = buscar_por_texto(p.orden, p.botones)
        origen = "modelo"
    except (requests.RequestException, ValueError, KeyError, AttributeError):
        indice, respuesta, origen = buscar_por_texto(p.orden, p.botones), "", "texto"

    if indice is None:
        return {"accion": "nada", "origen": origen,
                "respuesta": respuesta or "No encontré un botón que coincida en esta pantalla."}
    b = p.botones[indice]
    return {"accion": "tocar", "origen": origen, "nombre": b.nombre, "x": b.x, "y": b.y,
            "respuesta": respuesta or f"Tocando {b.nombre}."}


AVISO_REMEDIO = " Antes de tomarlo, confirmalo con tu médico o farmacéutico."
TIPOS_FOTO = {"remedio", "factura", "carta", "otro", "ilegible"}


def mirar_foto(imagen, pregunta, contexto):
    prompt = (
        "Sos los ojos de una persona con baja visión. Mirá la foto y ayudala.\n"
        "Reglas: leé el texto tal cual está impreso, NO inventes números ni datos. "
        "Si algo no se lee claro, decilo. Hablá simple, en español argentino, máximo 3 frases.\n"
        "- Remedio: nombre, miligramos y lo que diga la caja sobre la dosis.\n"
        "- Factura: cuánto hay que pagar y hasta qué fecha.\n"
        "- Carta: quién la manda y qué pide.\n"
        + (f"Pregunta de la persona: {pregunta}\n" if pregunta else "")
        + (f"Datos que cargó la familia:\n{contexto}\n" if contexto else "")
        + 'Respondé SOLO un JSON: {"tipo": "remedio|factura|carta|otro|ilegible", '
        '"respuesta": "<lo que se le dice en voz alta>"}'
    )
    r = requests.post(f"{OLLAMA_URL}/api/chat", json={
        "model": MODELO_VISION,
        "messages": [{"role": "user", "content": prompt, "images": [imagen]}],
        "format": "json",
        "stream": False,
        "options": {"temperature": 0},
    }, timeout=300)
    r.raise_for_status()
    return json.loads(r.json()["message"]["content"])


@app.post("/leer_foto")
def leer_foto(p: PedidoFoto, x_clave: str = Header("")):
    """Lee un papel o un remedio. El código, no el modelo, agrega el aviso de los remedios."""
    col = coleccion(p.tenant_id, x_clave)
    if not 100 < len(p.imagen_base64) < 12_000_000:
        raise HTTPException(400, "Imagen vacía o demasiado grande")
    try:
        contexto = ""
        if col.count():
            consulta = p.pregunta or "remedios, dosis y datos de la persona"
            res = col.query(query_embeddings=embeber([consulta]), n_results=min(3, col.count()))
            contexto = "\n".join(res["documents"][0])
        salida = mirar_foto(p.imagen_base64, p.pregunta, contexto)
        tipo = salida.get("tipo") if salida.get("tipo") in TIPOS_FOTO else "otro"
        respuesta = str(salida.get("respuesta") or "").strip()[:600]
    except (requests.RequestException, ValueError, KeyError, AttributeError):
        return {"tipo": "error", "respuesta": "No pude leer la foto ahora. Probá de nuevo en un rato."}

    if tipo == "ilegible" or not respuesta:
        return {"tipo": "ilegible",
                "respuesta": "No se lee bien. Acercá un poco más el papel, con buena luz, y probá de nuevo."}
    if tipo == "remedio":
        respuesta += AVISO_REMEDIO
    return {"tipo": tipo, "respuesta": respuesta}


@app.post("/rag/{tenant_id}/cargar")
def rag_cargar(tenant_id: str, p: PedidoCargar, x_clave: str = Header("")):
    col = coleccion(tenant_id, x_clave)
    textos = [t for t in p.textos if t.strip()]
    if not textos:
        raise HTTPException(400, "No hay textos para cargar")
    inicio = col.count()
    col.add(ids=[f"doc_{inicio + i}" for i in range(len(textos))],
            documents=textos, embeddings=embeber(textos))
    return {"tenant_id": tenant_id, "cargados": len(textos), "total": col.count()}


@app.post("/rag/{tenant_id}/consultar")
def rag_consultar(tenant_id: str, p: PedidoConsultar, x_clave: str = Header("")):
    col = coleccion(tenant_id, x_clave)
    if not col.count():
        return {"resultados": []}
    res = col.query(query_embeddings=embeber([p.pregunta]),
                    n_results=max(1, min(p.cantidad, col.count())))
    return {"resultados": res["documents"][0]}
