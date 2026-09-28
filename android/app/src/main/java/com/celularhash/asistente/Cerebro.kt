package com.celularhash.asistente

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Llamadas al cerebro del servidor. Devuelve null si no hay conexión o responde con error. */
object Cerebro {

    /** Agrega tenant_id y la clave del cliente. No llamar desde el hilo principal. */
    fun post(context: Context, ruta: String, cuerpo: JSONObject, esperaMs: Int = 60000): JSONObject? {
        val prefs = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
        val base = prefs.getString(Config.URL, null)?.takeIf { it.startsWith("http") } ?: return null
        return try {
            cuerpo.put("tenant_id", prefs.getString(Config.TENANT, "demo"))
            val con = (URL("$base$ruta").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = esperaMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Clave", prefs.getString(Config.CLAVE, ""))
            }
            con.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
            if (con.responseCode != 200) return null
            JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        }
    }
}
