package com.celularhash.asistente

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "Leer papel": la pantalla entera es la cámara y el botón a la vez.
 * Se apunta a un papel o remedio, se toca en cualquier lado, y la respuesta
 * se dice en voz alta y se muestra en letra grande.
 */
class CamaraActivity : ComponentActivity() {

    private val trabajo = Executors.newSingleThreadExecutor()
    private lateinit var visor: PreviewView
    private lateinit var texto: TextView
    private var captura: ImageCapture? = null
    private var tts: TextToSpeech? = null
    private var ocupado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        visor = PreviewView(this)
        texto = TextView(this).apply {
            textSize = 30f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER
            text = "Apuntá al papel y tocá la pantalla"
        }
        setContentView(FrameLayout(this).apply {
            addView(visor)
            addView(texto, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { sacarFoto() }
        })
        tts = TextToSpeech(this) { estado ->
            if (estado == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "AR")
                decir("Apuntá al papel o al remedio y tocá la pantalla.")
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            prenderCamara()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) prenderCamara()
        else decir("Sin permiso de cámara no puedo leer papeles.")
    }

    override fun onDestroy() {
        tts?.shutdown()
        trabajo.shutdown()
        super.onDestroy()
    }

    private fun prenderCamara() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            val proveedor = futuro.get()
            val vista = Preview.Builder().build().also { it.setSurfaceProvider(visor.surfaceProvider) }
            captura = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            proveedor.unbindAll()
            proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, vista, captura)
        }, mainExecutor)
    }

    private fun sacarFoto() {
        val c = captura ?: return
        if (ocupado) {
            decir("Todavía estoy leyendo, esperá.")
            return
        }
        ocupado = true
        avisar()
        c.takePicture(trabajo, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imagen: ImageProxy) {
                val base64 = aBase64(imagen)
                imagen.close()
                runOnUiThread { mostrarYDecir("Leyendo, esperá un momento.") }
                val datos = Cerebro.post(this@CamaraActivity, "/leer_foto",
                    JSONObject().put("imagen_base64", base64), esperaMs = 300000)
                val frase = datos?.optString("respuesta")?.takeIf { it.isNotBlank() }
                    ?: "No me pude conectar con el servidor. Para leer papeles necesito internet."
                runOnUiThread {
                    ocupado = false
                    mostrarYDecir(frase)
                }
            }

            override fun onError(e: ImageCaptureException) {
                runOnUiThread {
                    ocupado = false
                    mostrarYDecir("No pude sacar la foto. Probá de nuevo.")
                }
            }
        })
    }

    /** Endereza la foto y la achica a 1280 px: alcanza para leer y viaja rápido. */
    private fun aBase64(imagen: ImageProxy): String {
        var bmp = imagen.toBitmap()
        val grados = imagen.imageInfo.rotationDegrees
        if (grados != 0) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(grados.toFloat()) }, true)
        }
        val escala = 1280f / maxOf(bmp.width, bmp.height)
        if (escala < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * escala).toInt(), (bmp.height * escala).toInt(), true)
        }
        val salida = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, salida)
        return Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)
    }

    private fun avisar() {
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        val tono = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tono.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        texto.postDelayed({ tono.release() }, 300)
    }

    private fun mostrarYDecir(frase: String) {
        texto.text = frase
        decir(frase)
    }

    private fun decir(frase: String) {
        tts?.speak(frase, TextToSpeech.QUEUE_FLUSH, null, "camara")
    }
}
