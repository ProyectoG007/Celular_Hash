package com.celularhash.asistente

import android.Manifest
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * El asistente vive en el celular: tocás el botón de accesibilidad, suena un pitido,
 * decís la orden, lee los botones de la pantalla, le pregunta al cerebro (Docker en
 * el servidor) cuál tocar, lo toca y lo dice en voz alta.
 * Si el cerebro no responde, usa la búsqueda por texto local (igual que asistente.py).
 */
class AsistenteService : AccessibilityService() {

    private data class Elemento(val nombre: String, val nodo: AccessibilityNodeInfo, val x: Int, val y: Int)

    private val principal = Handler(Looper.getMainLooper())
    private val red = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var reconocedor: SpeechRecognizer? = null
    private var ocupado = false

    private val botonAccesibilidad = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = iniciar()
    }

    override fun onServiceConnected() {
        tts = TextToSpeech(this) { estado ->
            if (estado == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("es", "AR"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("es"))
                }
            }
        }
        accessibilityButtonController.registerAccessibilityButtonCallback(botonAccesibilidad)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(botonAccesibilidad)
        reconocedor?.destroy()
        tts?.shutdown()
        red.shutdown()
        super.onDestroy()
    }

    private fun iniciar() {
        if (ocupado) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            hablar("Falta permitir el micrófono. Abrí la app del asistente.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            hablar("Este celular no tiene reconocimiento de voz.")
            return
        }
        ocupado = true
        avisarQuePuedeHablar()
        principal.postDelayed({ escuchar() }, 300)
    }

    /** Señal sin pantalla: vibración + pitido, para que se entienda sin ver. */
    private fun avisarQuePuedeHablar() {
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        val tono = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tono.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        principal.postDelayed({ tono.release() }, 300)
    }

    private fun escuchar() {
        reconocedor?.destroy()
        reconocedor = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (texto.isNullOrBlank()) terminar("No entendí ninguna orden.") else procesar(texto)
                }

                override fun onError(error: Int) = terminar("No te escuché. Probá de nuevo.")
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }
    }

    private fun procesar(orden: String) {
        val elementos = leerPantalla()
        val o = orden.lowercase()
        if (listOf("foto", "cámara", "camara", "papel", "remedio", "factura", "carta", "leé esto", "lee esto").any { it in o }) {
            ocupado = false
            startActivity(Intent(this, CamaraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (listOf("qué hay", "que hay", "pantalla", "describ").any { it in o }) {
            terminar(describir(elementos))
            return
        }
        if (elementos.isEmpty()) {
            terminar("No detecto botones en esta pantalla. Puede estar cargando.")
            return
        }
        red.execute {
            val respuesta = preguntarAlCerebro(orden, elementos)
            principal.post {
                if (respuesta == null) {
                    val local = buscarPorTexto(orden, elementos)
                    if (local == null) terminar("Dijiste $orden, pero no encontré un botón que coincida.")
                    else { tocar(local); terminar("Tocando ${local.nombre}.") }
                } else {
                    val (elegido, frase) = respuesta
                    elegido?.let { tocar(it) }
                    terminar(frase)
                }
            }
        }
    }

    /** Recorre la pantalla y junta cada botón con su nombre visible y su centro. */
    private fun leerPantalla(): List<Elemento> {
        val raiz = rootInActiveWindow ?: return emptyList()
        val vistos = HashSet<String>()
        val salida = ArrayList<Elemento>()
        val pila = ArrayDeque<AccessibilityNodeInfo>().apply { add(raiz) }
        while (pila.isNotEmpty()) {
            val nodo = pila.removeLast()
            for (i in 0 until nodo.childCount) nodo.getChild(i)?.let { pila.add(it) }
            val nombre = (nodo.text?.toString()?.takeIf { it.isNotBlank() }
                ?: nodo.contentDescription?.toString())?.trim().orEmpty()
            if (nombre.isEmpty() || RUIDO.containsMatchIn(nombre)) continue
            val tocable = tocableMasCercano(nodo) ?: continue
            val r = Rect().also { tocable.getBoundsInScreen(it) }
            if (r.isEmpty || !vistos.add(r.toShortString())) continue
            salida.add(Elemento(nombre.take(80), tocable, r.centerX(), r.centerY()))
        }
        return salida.sortedWith(compareBy({ it.y }, { it.x }))
    }

    private fun tocableMasCercano(nodo: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var actual: AccessibilityNodeInfo? = nodo
        repeat(5) {
            val n = actual ?: return null
            if (n.isClickable) return n
            actual = n.parent
        }
        return null
    }

    private fun describir(elementos: List<Elemento>): String {
        if (elementos.isEmpty()) return "No detecto nada en esta pantalla. Puede estar cargando."
        val nombres = elementos.take(5).joinToString(", ") { it.nombre }
        return "Hay ${elementos.size} botones. Los principales son: $nombres."
    }

    private fun buscarPorTexto(orden: String, elementos: List<Elemento>): Elemento? {
        val o = orden.lowercase().trim()
        return elementos
            .filter { it.nombre.length >= 3 && it.nombre.lowercase().trim() in o }
            .maxByOrNull { it.nombre.length }
    }

    /** Devuelve (botón o null, frase), o null si el cerebro no responde. Corre fuera del hilo principal. */
    private fun preguntarAlCerebro(orden: String, elementos: List<Elemento>): Pair<Elemento?, String>? {
        val cuerpo = JSONObject()
            .put("orden", orden)
            .put("botones", JSONArray().apply {
                elementos.forEach { put(JSONObject().put("nombre", it.nombre).put("x", it.x).put("y", it.y)) }
            })
        val datos = Cerebro.post(this, "/interpretar", cuerpo) ?: return null
        val frase = datos.optString("respuesta")
        if (datos.optString("accion") != "tocar") return null to frase
        val x = datos.optInt("x", -1)
        val y = datos.optInt("y", -1)
        // Solo se toca un botón que realmente está en la pantalla.
        return elementos.firstOrNull { it.x == x && it.y == y } to frase
    }

    private fun tocar(e: Elemento) {
        if (e.nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val trazo = Path().apply { moveTo(e.x.toFloat(), e.y.toFloat()) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(trazo, 0, 60))
                .build(), null, null
        )
    }

    private fun terminar(frase: String) {
        ocupado = false
        hablar(frase)
    }

    private fun hablar(frase: String) {
        tts?.speak(frase, TextToSpeech.QUEUE_FLUSH, null, "asistente")
    }

    companion object {
        /** Mismo filtro que lector_pantalla.py: días, reloj, clima. */
        private val RUIDO = Regex(
            "^(lun|mar|mi[eé]|jue|vie|s[aá]b|dom)|^\\d{1,2}\\n?\\d{2}$|soleado|nublado|lluvi|tormenta|nevad",
            RegexOption.IGNORE_CASE
        )
    }
}
