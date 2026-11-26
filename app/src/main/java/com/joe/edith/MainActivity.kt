package com.joe.edith

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

/** One simple screen: home computer address, phone code, sensitivity, start/stop. */
class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: SharedPreferences
    private lateinit var server: EditText
    private lateinit var code: EditText
    private lateinit var glasses: Switch
    private lateinit var sens: SeekBar
    private lateinit var sensLabel: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button

    private val accent = Color.parseColor("#39D7FF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(buildUi())
        load()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
        refresh()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        runOnUiThread { refresh() }
    }

    // ---------------------------------------------------------------- screen
    @SuppressLint("SetTextI18n")
    private fun buildUi(): View {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#05080C"))
        }
        col.addView(TextView(this).apply {
            text = "E.D.I.T.H."
            textSize = 34f
            setTextColor(accent)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.2f
        })
        col.addView(label("Say \"Hey Jarvis\" anywhere. EDITH sends it to your JARVIS PC at home."))

        col.addView(label("Home computer address (Tailscale)"))
        server = field("100.x.x.x:8765", InputType.TYPE_TEXT_VARIATION_URI)
        col.addView(server)
        col.addView(label("Phone code (from JARVIS)"))
        code = field("code", InputType.TYPE_CLASS_TEXT)
        col.addView(code)

        glasses = Switch(this).apply {
            text = "Use my Bluetooth glasses' mic and speaker"
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(8))
        }
        col.addView(glasses)

        sensLabel = label("")
        col.addView(sensLabel)
        sens = SeekBar(this).apply {
            max = 50                                         // 0.30 .. 0.80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { showSens() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(sens)

        toggle = button("Start EDITH") { if (prefs.getBoolean(Prefs.RUNNING, false)) stop() else start() }
        col.addView(toggle)
        col.addView(button("Test connection") { test() })
        col.addView(button("Keep EDITH running (battery setting)") { askBattery() })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#B8E9FF"))
            setPadding(0, dp(20), 0, 0)
        }
        col.addView(status)
        col.addView(label("If you change settings, stop and start EDITH again."))
        return ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#05080C")) }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.parseColor("#8FA3B0"))
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#55636D"))
        isSingleLine = true
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(Color.BLACK)
        setBackgroundColor(accent)
        gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(12)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n")
    private fun showSens() {
        val v = 0.30f + sens.progress / 100f
        sensLabel.text = "Wake word strictness: %.2f  (lower = hears you easier, higher = fewer false alarms)".format(v)
    }

    private fun load() {
        server.setText(prefs.getString(Prefs.SERVER, ""))
        code.setText(prefs.getString(Prefs.CODE, ""))
        glasses.isChecked = prefs.getBoolean(Prefs.GLASSES, true)
        sens.progress = ((prefs.getFloat(Prefs.THRESHOLD, 0.5f) - 0.30f) * 100).toInt().coerceIn(0, 50)
        showSens()
    }

    private fun save() {
        prefs.edit()
            .putString(Prefs.SERVER, server.text.toString().trim())
            .putString(Prefs.CODE, code.text.toString().trim())
            .putBoolean(Prefs.GLASSES, glasses.isChecked)
            .putFloat(Prefs.THRESHOLD, 0.30f + sens.progress / 100f)
            .apply()
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val on = prefs.getBoolean(Prefs.RUNNING, false)
        toggle.text = if (on) "Stop EDITH" else "Start EDITH"
        status.text = prefs.getString(Prefs.STATUS, "Off")
    }

    // ---------------------------------------------------------------- actions
    private fun start() {
        save()
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) need += Manifest.permission.BLUETOOTH_CONNECT
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, EdithService::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, EdithService::class.java))
        } else {
            prefs.edit().putString(Prefs.STATUS, "EDITH needs the microphone permission to hear you.").apply()
        }
    }

    private fun stop() {
        startService(Intent(this, EdithService::class.java).setAction(EdithService.ACTION_STOP))
    }

    private fun test() {
        save()
        val s = Prefs.server(prefs)
        val c = prefs.getString(Prefs.CODE, "")!!
        prefs.edit().putString(Prefs.STATUS, "Testing…").apply()
        thread {
            val msg = try {
                if (s.isEmpty()) "Type your home computer's address first."
                else "Connected. JARVIS says: " + Jarvis.ask(s, c, "Are you there? Answer in a few words.")
            } catch (e: Exception) {
                "Couldn't connect: ${e.message ?: e.javaClass.simpleName}. Is Tailscale on, and is JARVIS running?"
            }
            prefs.edit().putString(Prefs.STATUS, msg).apply()
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putString(Prefs.STATUS, "Battery setting is already good.").apply()
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
}
