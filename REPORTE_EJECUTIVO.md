# Reporte Ejecutivo — Proyecto Celular_Hash / Asistencia IA

**Fecha:** 27-28 de septiembre de 2026
**Estado general:** Prueba de concepto funcional, con hardware real. No es producto terminado.

---

## 1. Qué es este proyecto

Un sistema que usa un modelo de IA local para operar un celular Android real
(tocarlo, escribir, leer su pantalla) sin depender de APIs pagas ni de que la
app tenga una integración oficial. Se está usando como base para dos líneas:

1. **Automatización comercial** (idea original: leer WhatsApp, cargar pedidos,
   manejar apps sin API — ver el chat de Gemini origen del proyecto).
2. **Asistencia para personas con problemas de visión** (línea activa desde
   hoy: `asistencia_ia/1_navegacion_por_voz/`).

---

## 2. Stack tecnológico

| Capa | Tecnología elegida | Por qué | Estado |
|---|---|---|---|
| Puente PC ↔ celular | ADB (Android Debug Bridge) | Oficial de Google, gratis, no requiere root | ✅ Verificado |
| Lectura de pantalla | `uiautomator dump` (XML) | Viene con Android, da coordenadas exactas de cada botón | ✅ Verificado |
| Ejecución de toques | `adb shell input tap/text` | Simula un dedo humano, funciona en cualquier app | ✅ Verificado |
| Reconocimiento de voz | Vosk (modelo "small", español) | 100% local, no manda audio a ningún servidor | ✅ Verificado, con limitaciones |
| Texto a voz | pyttsx3 + voz de Windows (SAPI5) | Ya viene en Windows, cero instalación pesada | ✅ Verificado |
| Memoria / RAG | ChromaDB + sentence-transformers | Base vectorial local para "recordar" reglas o datos propios | ✅ Verificado (motor), ⚠️ sin datos reales cargados |
| Cerebro / razonamiento | Ollama (modelo local tipo Llama/Qwen) | Decidiría qué acción tomar según el pedido | ❌ No conectado todavía |
| Entorno de ejecución | Python 3.13 + venv aislado | Se encontró la instalación global de Python rota (paquetes corruptos de otro proyecto); se armó un entorno separado | ✅ Resuelto |

---

## 3. Qué se probó HOY, con evidencia real (no supuesto)

- ✅ ADB detecta el celular real (Moto G24 Power) por USB.
- ✅ El sistema lee la pantalla del celular y calcula coordenadas exactas de cada botón visible.
- ✅ El sistema toca un botón real de la pantalla y la app correspondiente se abre (confirmado consultando qué app quedó en primer plano, no solo "el comando no tiró error").
- ✅ Reintento automático cuando la pantalla está en transición o video (caso Instagram Reels).
- ✅ Texto a voz: el sistema describe la pantalla en audio real, confirmado por escucha directa.
- ✅ Reconocimiento de voz: el sistema entiende una orden dicha en español ("abrí la billetera") con el modelo local, sin internet.
- ✅ **Circuito cerrado end-to-end:** orden hablada → identificación del botón → toque real → confirmación en voz. Probado abriendo WhatsApp por orden de voz.
- ✅ Filtro de "ruido" de la interfaz (reloj, clima, fecha) para que la descripción hablada no incluya cosas irrelevantes.
- ✅ Entorno RAG (ChromaDB + embeddings) probado de punta a punta: guarda y recupera texto correctamente.

## 4. Qué quedó demostrado como PROBLEMA real (no hipotético)

- ⚠️ **Vosk (modelo "small") reconoce mal palabras en inglés.** "WhatsApp" se
  interpretó como "sap" o "averiguase" en varias pruebas. Con frases 100% en
  español funciona bien.
- ⚠️ **El micrófono usado es el de la PC, no el del celular.** Para un asistente
  que "vive" en el celular (caso de uso real de accesibilidad, alguien que
  lleva el teléfono en el bolsillo) esto no sirve tal como está.
- ⚠️ Apps con contenido animado (Instagram) a veces no responden al primer
  intento de lectura de pantalla — mitigado con reintento, no eliminado.
- ⚠️ El nivel de captura del micrófono es bajo por defecto en esta notebook
  (se midió ~0-3% del rango de volumen); funciona, pero con poco margen.

## 5. Qué es solo IDEA, sin probar (viene del chat de Gemini original)

Todo lo siguiente aparece en `Gemini-Arquitectura local de voz-*.md` como
scripts pegados, pero **no se ejecutó contra hardware real** hasta hoy, y buena
parte sigue sin probarse:

- ❌ Servidor "Cerebro Central" con FastAPI + Ollama real (se probó el servidor
  FastAPI con una respuesta simulada/hardcodeada, nunca con un modelo real
  respondiendo).
- ❌ Visión por cámara para seguridad (detección de armas con YOLO) — es puro
  diseño conceptual, cero código probado.
- ❌ Nodo de voz en Raspberry Pi — nunca se tuvo el hardware a mano.
- ❌ Automatización de WhatsApp Business para atención al cliente — el script
  existe (`cliente_whatsapp.py` en el chat original) pero no se probó.
- ❌ Empaquetado comercial (instalador `.bat` para notebooks de clientes) — sin
  probar; además reinstalaría el mismo `torch` que estaba roto en esta PC.

---

## 6. Aplicación a accesibilidad visual — evaluación honesta

**El hueco de mercado real:** Android ya trae TalkBack (lector de pantalla
gratuito y maduro). No tiene sentido competir contra eso. El aporte real de
este proyecto sería para el caso donde TalkBack lee mal — apps con botones sin
etiquetar, interfaces con demasiados elementos donde hay que escuchar todo en
orden para encontrar lo importante.

**Lo que hoy demuestra el prototipo:** que se puede describir una pantalla de
forma priorizada (no lineal como TalkBack) y ejecutar una acción por orden de
voz, todo local. Es una prueba de concepto válida, no un producto.

**Lo que falta para que sea usable por una persona con baja visión de verdad:**
- Una señal de "podés hablar ahora" que no dependa de leer texto en pantalla
  (un sonido, una vibración). Hoy el sistema avisa por texto en consola, algo
  que el propio usuario objetivo no podría ver.
- Que el micrófono esté en el dispositivo que la persona realmente usa (el
  celular), no en una notebook aparte.
- Ampliar el vocabulario de órdenes reconocidas más allá de "abrí X".
- Probar con el modelo grande de Vosk (más preciso, más pesado) en vez del chico.

---

## 7. Próximos pasos posibles (para decidir, no decisión tomada)

1. Resolver el problema de "dónde escucha" el asistente (PC vs. celular) antes
   de seguir sumando funciones.
2. Conectar Ollama de verdad al servidor Cerebro (hoy es una simulación).
3. Cargar datos reales al RAG (por ahora el motor anda, pero está vacío).
4. Evaluar early con una persona con baja visión real si la priorización de
   botones tiene sentido para su forma de navegar — antes de invertir más
   tiempo de desarrollo.
