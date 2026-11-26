package com.joe.edith

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Settings, saved on the phone. */
object Prefs {
    const val NAME = "edith"
    const val SERVER = "server"          // e.g. http://100.101.102.103:8765 (the PC's Tailscale address)
    const val CODE = "code"              // the phone code JARVIS shows (data/phone_code.txt)
    const val THRESHOLD = "threshold"    // wake word sensitivity, 0.3 - 0.8
    const val GLASSES = "glasses"        // use the Bluetooth glasses' mic + speaker
    const val STATUS = "status"          // written by the service, shown in the app
    const val RUNNING = "running"

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun server(p: SharedPreferences): String = p.getString(SERVER, "")!!.trim().trimEnd('/').let {
        if (it.isEmpty() || it.startsWith("http")) it else "http://$it"
    }
}

/** Talks to the JARVIS PC at home. */
object Jarvis {
    class JarvisError(message: String) : Exception(message)

    /** Send what you said, get JARVIS's answer back. */
    fun ask(server: String, code: String, text: String): String {
        val body = JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)
        val conn = open("$server/api/edith", code, 30_000)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body) }
        val status = conn.responseCode
        val text2 = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (status == 401 || status == 403) throw JarvisError("wrong phone code")
        if (status !in 200..299) throw JarvisError("JARVIS answered $status")
        return JSONObject(text2).optString("reply", "")
    }

    /** The wake word needs three small model files. Get them from the PC, or from openWakeWord's site. */
    fun ensureModels(ctx: Context, server: String, code: String, status: (String) -> Unit): File {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        for (name in MODELS) {
            val f = File(dir, name)
            if (f.exists() && f.length() > 1000) continue
            status("Downloading wake word ($name)…")
            val sources = mutableListOf<String>()
            if (server.isNotEmpty()) sources += "$server/api/edith/model/" + URLEncoder.encode(name, "UTF-8")
            sources += "$GITHUB/$name"
            var ok = false
            for (src in sources) {
                try {
                    download(src, if (server.isNotEmpty() && src.startsWith(server)) code else "", f)
                    if (f.length() > 1000) { ok = true; break }
                } catch (_: Exception) {
                }
            }
            if (!ok) {
                f.delete()
                throw JarvisError("couldn't download the wake word files")
            }
        }
        return dir
    }

    val MODELS = listOf("melspectrogram.onnx", "embedding_model.onnx", "hey_jarvis_v0.1.onnx")
    private const val GITHUB = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"

    private fun download(url: String, code: String, out: File) {
        var u = url
        repeat(5) {                                   // follow GitHub's redirects
            val c = open(u, code, 60_000)
            c.instanceFollowRedirects = false
            val s = c.responseCode
            if (s in 300..399) {
                u = c.getHeaderField("Location") ?: throw JarvisError("bad redirect")
                c.disconnect()
                return@repeat
            }
            if (s !in 200..299) { c.disconnect(); throw JarvisError("download failed $s") }
            val tmp = File(out.path + ".part")
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            c.disconnect()
            tmp.renameTo(out)
            return
        }
        throw JarvisError("too many redirects")
    }

    private fun open(url: String, code: String, timeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "EDITH/1.0")
        if (code.isNotEmpty()) c.setRequestProperty("X-Jarvis-Code", code)
        return c
    }
}
