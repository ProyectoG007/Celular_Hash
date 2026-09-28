import json
import os
import queue
import time

import sounddevice as sd
import vosk

RUTA_MODELO = os.path.join(os.path.dirname(__file__), "modelo_voz_es")


def escuchar_orden(segundos_max=20, cuenta_regresiva=True):
    """Graba del micrófono hasta que detecta silencio o se acaba el tiempo
    máximo, y devuelve el texto reconocido en español. Todo corre local,
    sin mandar audio a ningún servidor."""
    modelo = vosk.Model(RUTA_MODELO)
    reconocedor = vosk.KaldiRecognizer(modelo, 16000)

    if cuenta_regresiva:
        for n in (3, 2, 1):
            print(f"Preparate... {n}")
            time.sleep(1)

    cola_audio = queue.Queue()

    def callback(indata, frames, time_info, status):
        cola_audio.put(bytes(indata))

    texto_final = ""
    with sd.RawInputStream(samplerate=16000, blocksize=8000, dtype="int16",
                            channels=1, callback=callback):
        print("¡AHORA! Escuchando... decí una orden.")
        bloques_leidos = 0
        max_bloques = int(segundos_max * 16000 / 8000)
        while bloques_leidos < max_bloques:
            data = cola_audio.get()
            bloques_leidos += 1
            if reconocedor.AcceptWaveform(data):
                resultado = json.loads(reconocedor.Result())
                if resultado.get("text"):
                    texto_final = resultado["text"]
                    break

        if not texto_final:
            resultado_final = json.loads(reconocedor.FinalResult())
            texto_final = resultado_final.get("text", "")

    return texto_final


if __name__ == "__main__":
    texto = escuchar_orden()
    print(f"Se entendió: '{texto}'")
