package com.joe.edith

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Runs all the time (with a notification). Listens for "Hey Jarvis", then hears your request,
 * sends it to your JARVIS PC at home, and speaks the answer, through your glasses when they're connected.
 */
class EdithService : Service() {
    companion object {
        const val ACTION_STOP = "com.joe.edith.STOP"
        private const val CHANNEL = "edith"
        private const val NOTE_ID = 7
        private const val RATE = 16000
        private const val FOLLOW_UPS = 3          // keep talking without the wake word this many times
    }

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var audio: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var tone: ToneGenerator? = null

    @Volatile private var running = false
    @Volatile private var listening = false      // true = the wake word loop owns the mic
    private var loop: Thread? = null
    private var followUps = 0
    private var useGlasses = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "EDITH", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }
        val note = note("Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTE_ID, note)
        if (!running) start()
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start / stop
    private fun start() {
        running = true
        val p = Prefs.get(this)
        p.edit().putBoolean(Prefs.RUNNING, true).apply()
        useGlasses = p.getBoolean(Prefs.GLASSES, true)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "edith:listen").apply { acquire() }
        tone = try { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80) } catch (_: Exception) { null }
        tts = TextToSpeech(this) { ok ->
            if (ok == TextToSpeech.SUCCESS) main.post { setUpVoice() }
        }
        if (useGlasses) routeToGlasses(true)
        net.execute {
            try {
                val dir = Jarvis.ensureModels(this, Prefs.server(p), p.getString(Prefs.CODE, "")!!, ::status)
                val ww = WakeWord(dir)
                main.post { if (running) startWakeLoop(ww) else ww.close() }
            } catch (e: Exception) {
                status("Problem: ${e.message}")
            }
        }
    }

    private fun stopEverything() {
        running = false
        listening = false
        loop?.interrupt()
        loop = null
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
        tone?.release(); tone = null
        routeToGlasses(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Prefs.get(this).edit().putBoolean(Prefs.RUNNING, false).putString(Prefs.STATUS, "Off").apply()
    }

    // ---------------------------------------------------------------- glasses (Bluetooth headset mic)
    @Suppress("DEPRECATION")
    private fun routeToGlasses(on: Boolean) {
        try {
            if (on) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val bt = audio.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    if (bt != null) audio.setCommunicationDevice(bt)
                } else {
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
                else { audio.isBluetoothScoOn = false; audio.stopBluetoothSco() }
                audio.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {
        }
    }

    private fun glassesConnected(): Boolean =
        Build.VERSION.SDK_INT >= 31 && audio.communicationDevice?.let {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } == true

    // ---------------------------------------------------------------- wake word loop
    @SuppressLint("MissingPermission")
    private fun startWakeLoop(ww: WakeWord) {
        listening = true
        status(if (glassesConnected()) "Listening through your glasses for \"Hey Jarvis\"" else "Listening for \"Hey Jarvis\"")
        loop = Thread {
            val threshold = Prefs.get(this).getFloat(Prefs.THRESHOLD, 0.5f)
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = ShortArray(WakeWord.CHUNK)
            var rec: AudioRecord? = null
            try {
                while (running) {
                    if (!listening) {                              // someone else (speech/voice) has the mic
                        rec?.let { it.stop(); it.release() }
                        rec = null
                        Thread.sleep(100)
                        continue
                    }
                    if (rec == null) {
                        val src = if (useGlasses) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                                  else MediaRecorder.AudioSource.VOICE_RECOGNITION
                        val fresh = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            maxOf(min, WakeWord.CHUNK * 4))
                        if (fresh.state != AudioRecord.STATE_INITIALIZED) {
                            fresh.release()
                            Thread.sleep(700)                      // the mic is probably still busy: try again
                            continue
                        }
                        fresh.startRecording()
                        ww.reset()
                        rec = fresh
                    }
                    val r = rec ?: continue
                    var got = 0
                    while (got < WakeWord.CHUNK && listening && running) {
                        val n = r.read(chunk, got, WakeWord.CHUNK - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got < WakeWord.CHUNK) continue
                    if (ww.process(chunk) >= threshold) {
                        listening = false
                        followUps = 0
                        main.post { heard() }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                status("Wake word stopped: ${e.message}")
            } finally {
                rec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
                ww.close()
            }
        }.apply { name = "edith-wake"; start() }
    }

    private fun backToWaiting() {
        if (!running) return
        status(if (glassesConnected()) "Listening through your glasses for \"Hey Jarvis\"" else "Listening for \"Hey Jarvis\"")
        listening = true
    }

    // ---------------------------------------------------------------- your request
    private fun heard() {
        if (!running) return
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
        main.postDelayed({ listenForRequest() }, 250)
    }

    private fun listenForRequest() {
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Speech recognition isn't available on this phone.") { backToWaiting() }
            return
        }
        status("Listening…")
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (text.isEmpty()) backToWaiting() else send(text)
            }
            override fun onError(error: Int) { backToWaiting() }   // nothing said (or recognizer busy)
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status("Thinking…") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
        }
        r.startListening(i)
    }

    private fun send(text: String) {
        status("You: $text")
        val p = Prefs.get(this)
        val server = Prefs.server(p)
        val code = p.getString(Prefs.CODE, "")!!
        net.execute {
            val reply = try {
                if (server.isEmpty()) "Set the address of your home computer in the app first."
                else Jarvis.ask(server, code, text).ifBlank { "Done." }
            } catch (e: Jarvis.JarvisError) {
                if (e.message == "wrong phone code") "The phone code is wrong. Check it in the app." else "Trouble reaching home: ${e.message}."
            } catch (e: Exception) {
                "I can't reach the house right now."
            }
            main.post {
                say(reply) {
                    if (followUps < FOLLOW_UPS) { followUps++; listenForRequest() } else backToWaiting()
                }
            }
        }
    }

    // ---------------------------------------------------------------- voice
    private fun setUpVoice() {
        val t = tts ?: return
        t.language = Locale.UK
        // prefer a British male voice (Google's "gbd"/"rjs"), else any British voice
        val uk = try { t.voices?.filter { it.locale.country == "GB" }.orEmpty() } catch (_: Exception) { emptyList() }
        val pick = uk.firstOrNull { it.name.contains("gbd") && !it.isNetworkConnectionRequired }
            ?: uk.firstOrNull { it.name.contains("rjs") && !it.isNetworkConnectionRequired }
            ?: uk.firstOrNull { !it.isNetworkConnectionRequired }
        if (pick != null) t.voice = pick
        t.setSpeechRate(1.05f)
        t.setPitch(0.95f)
        if (useGlasses) t.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        ttsReady = true
    }

    private fun say(text: String, then: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady) { then(); return }
        status("EDITH: $text")
        val id = "u" + System.currentTimeMillis()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) main.post { then() } }
            @Deprecated("old api") override fun onError(utteranceId: String?) { if (utteranceId == id) main.post { then() } }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) main.post { then() } }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    // ---------------------------------------------------------------- notification / status
    private fun status(s: String) {
        Prefs.get(this).edit().putString(Prefs.STATUS, s).apply()
        if (running) getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(s))
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, EdithService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("EDITH")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
