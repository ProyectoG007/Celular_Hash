package com.celularhash.asistente

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Pantalla de configuración: dirección del cerebro, cliente y clave. Se usa una sola vez. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)

        val url = campo("Dirección del cerebro", prefs.getString(Config.URL, Config.URL_DEFECTO))
        val tenant = campo("Cliente (tenant_id)", prefs.getString(Config.TENANT, "demo"))
        val clave = campo("Clave del cliente", prefs.getString(Config.CLAVE, ""))
        clave.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        val guardar = Button(this).apply {
            text = "Guardar"
            setOnClickListener {
                prefs.edit()
                    .putString(Config.URL, url.text.toString().trim().trimEnd('/'))
                    .putString(Config.TENANT, tenant.text.toString().trim())
                    .putString(Config.CLAVE, clave.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Guardado", Toast.LENGTH_SHORT).show()
            }
        }
        val microfono = Button(this).apply {
            text = "1. Permitir micrófono y cámara"
            setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 1)
            }
        }
        val accesibilidad = Button(this).apply {
            text = "2. Activar el asistente (Accesibilidad)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val ayuda = TextView(this).apply {
            text = "Después, tocá el botón de accesibilidad (abajo en la pantalla) " +
                "y decí una orden: \"abrí WhatsApp\", \"qué hay en pantalla\", \"leé este papel\".\n\n" +
                "O usá el ícono \"Leer papel\": apuntás y tocás la pantalla."
            setPadding(0, 32, 0, 0)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            listOf(url, tenant, clave, guardar, microfono, accesibilidad, ayuda).forEach { addView(it) }
        })
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Falta permitir el micrófono", Toast.LENGTH_SHORT).show()
        }
    }

    private fun campo(titulo: String, valor: String?) = EditText(this).apply {
        hint = titulo
        setText(valor)
        isSingleLine = true
    }
}

object Config {
    const val PREFS = "config"
    const val URL = "cerebro_url"
    const val TENANT = "tenant_id"
    const val CLAVE = "clave"
    const val URL_DEFECTO = "https://tu-servidor.com"
}
