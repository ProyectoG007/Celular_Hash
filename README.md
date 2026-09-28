# Celular_Hash — Agente de IA local que opera un celular Android

Proyecto de automatización local (sin nube, sin pago por token): una PC controla
un celular Android real por USB, viendo su pantalla y tocándola, con un asistente
de voz 100% local encima.

Todo lo documentado acá fue **ejecutado y verificado** contra hardware real
(notebook Windows + Moto G24 Power) el 27/28 de septiembre de 2026. No incluye
código sin probar.

---

## Estructura del repositorio

```
Celular_Hash/
├── Gemini-Arquitectura local de voz-*.md   # Chat original de Gemini (ideas teóricas, no todo probado)
├── platform-tools/                          # ADB (Android Debug Bridge), oficial de Google
├── venv/                                     # Entorno virtual Python, aislado del resto de la PC
├── cerebro/                                  # Cerebro en Docker: FastAPI + Ollama + RAG por cliente
├── docker-compose.yml                        # Levanta Ollama y el cerebro
├── android/                                  # App del celular: voz + lectura + toques, sin PC
├── asistencia_ia/
│   └── 1_navegacion_por_voz/                # Proyecto activo: asistente de voz + control de celular
│       ├── lector_pantalla.py               # Ve la pantalla del celular y la describe
│       ├── voz.py                           # Convierte texto a audio (texto-a-voz, local)
│       ├── oido.py                          # Escucha el micrófono y reconoce voz (local, Vosk)
│       ├── asistente.py                     # Une las tres piezas: escucha → busca botón → toca
│       └── modelo_voz_es/                   # Modelo de reconocimiento de voz en español (Vosk, ~38MB)
├── README.md                                 # Este archivo
└── REPORTE_EJECUTIVO.md                      # Estado del stack, qué hay y qué falta
```

---

## Requisitos

- Windows con Python 3.13 instalado.
- Un celular Android con **Opciones de Desarrollador** y **Depuración USB** activadas.
- Un cable USB que transfiera datos (no todos los cables sirven — algunos son
  solo de carga; si `adb devices` no detecta el teléfono, probar otro cable).
- Micrófono funcional en la notebook (para el asistente de voz).

## Instalación

Todo corre dentro de un entorno virtual aislado (`venv/`), para no tocar la
instalación global de Python de la PC (que tiene otros proyectos instalados).

```bash
# Desde la carpeta Celular_Hash/
python -m venv venv
./venv/Scripts/python.exe -m pip install fastapi uvicorn pydantic requests \
    sentence-transformers chromadb ollama vosk sounddevice pyttsx3
```

ADB (`platform-tools/adb.exe`) ya está incluido en el repo, bajado de la fuente
oficial: `https://dl.google.com/android/repository/platform-tools-latest-windows.zip`

## Conectar el celular

1. En el celular: `Configuración > Acerca del teléfono` → tocar 7 veces
   "Número de compilación" para activar Opciones de Desarrollador.
2. `Configuración > Opciones de desarrollador` → activar "Depuración USB".
3. Conectar por cable USB a la PC. Aceptar el cartel de autorización que
   aparece en el celular ("¿Permitir depuración USB?").
4. Verificar conexión:
   ```bash
   ./platform-tools/adb.exe devices
   ```
   Debe listar el dispositivo con estado `device` (no `unauthorized`).

## Cómo correr el asistente completo

```bash
cd asistencia_ia/1_navegacion_por_voz
../../venv/Scripts/python.exe asistente.py
```

El programa:
1. Da una cuenta regresiva (3, 2, 1) y avisa "¡AHORA!".
2. Escucha el micrófono de la **notebook** (no el del celular) durante 20 segundos.
3. Reconoce la orden dicha en español (ej. "abrí WhatsApp").
4. Lee la pantalla actual del celular por ADB.
5. Busca qué botón visible coincide con la orden.
6. Si lo encuentra: lo dice en voz alta y toca esa coordenada en el celular real.
7. Si no lo encuentra: avisa en voz que no hay coincidencia.

### Piezas por separado (para probar o depurar cada una)

- `python lector_pantalla.py` — solo ve la pantalla del celular y la describe en voz.
- `python oido.py` — solo escucha el micrófono y muestra qué entendió.
- `python voz.py` (no tiene modo standalone útil; se usa como módulo importado).

## Cerebro en Docker (modelo + RAG por cliente)

El celular, el micrófono y la voz quedan en Windows. En Docker va el cerebro:
Ollama (modelo `qwen2.5:3b`) + FastAPI + RAG (Chroma), con datos separados por cliente.

```bash
# Desde Celular_Hash/, con Docker Desktop abierto
docker compose up -d          # la primera vez baja ~2.5 GB de modelos
curl http://127.0.0.1:8000/salud
```

Después se corre `asistente.py` igual que siempre. Si el cerebro no responde,
usa la búsqueda local de antes. Variables opcionales en Windows:
`TENANT_ID` (cliente, por defecto `demo`) y `CEREBRO_URL`.

Cargar datos de un cliente al RAG:

```bash
curl -X POST http://127.0.0.1:8000/rag/ferreteria_01/cargar \
  -H "Content-Type: application/json" -d "{\"textos\": [\"Martillo: 5000 pesos\"]}"
```

El modelo solo devuelve un JSON con el número de botón; el código verifica que
ese botón exista antes de tocarlo.

## App Android (sin notebook)

`android/` es una app que hace todo desde el propio celular: escucha por su
micrófono, lee la pantalla, toca el botón y responde en voz alta. Le pregunta al
cerebro en el servidor; si no hay conexión, usa la búsqueda por texto local.

1. Instalar el APK en el celular.
2. Abrir la app: cargar dirección del servidor, cliente y clave. Tocar "Guardar".
3. "Permitir micrófono" y "Activar el asistente" (Ajustes > Accesibilidad).
4. Tocar el botón de accesibilidad de la pantalla: vibra y suena un pitido,
   y ahí se dice la orden ("abrí WhatsApp", "qué hay en pantalla").

Compilar: `cd android && ./gradlew assembleDebug` (sale en `app/build/outputs/apk/debug/`).

## Cerebro en un servidor (para clientes)

En un VPS con Docker (mínimo 8 GB de RAM, sin GPU):

```bash
cp ejemplo.env .env      # poner DOMINIO y una clave por cliente en CLAVES
docker compose --profile servidor up -d
```

Caddy saca el certificado HTTPS solo. Cada cliente usa su `tenant_id` y su
clave; sus datos del RAG no se mezclan con los de otro.

## Notas importantes de uso

- **El micrófono es el de la PC, no el del celular.** Hay que hablarle a la
  notebook. Esto fue una confusión real durante las pruebas — el asistente no
  escucha por el teléfono, salvo que se rediseñe para eso (ver reporte ejecutivo).
- El reconocimiento de voz (Vosk, modelo "small") es débil con palabras en
  inglés — "WhatsApp" se reconoce mal ("sap", "averiguase"). Funciona bien con
  frases 100% en español ("abrí la billetera").
- Apps con animaciones o video en vivo (Instagram) a veces fallan al leer la
  pantalla en el primer intento porque Android reporta "ocupado". El código
  reintenta automáticamente hasta 4 veces.

## Créditos de origen

La arquitectura conceptual (Protocolo B'H / "v10") surgió de una serie de
artículos de Andy Cufari (`andy.cm64.io`) sobre agentes de IA locales,
documentados en `Gemini-Arquitectura local de voz-*.md`. Ese documento contiene
ideas y scripts **no verificados** — el código de `asistencia_ia/` es la
implementación real, probada, derivada de esas ideas.
