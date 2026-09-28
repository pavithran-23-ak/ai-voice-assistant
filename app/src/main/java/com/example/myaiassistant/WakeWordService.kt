package com.example.myaiassistant

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class WakeWordService : Service() {
    private var porcupine: PorcupineManager? = null
    private lateinit var speaker: VoiceSpeaker
    private lateinit var router: ActionRouter
    private var voiceManager: VoiceManager? = null

    override fun onCreate() {
        super.onCreate()
        speaker = VoiceSpeaker(this)
        router = ActionRouter(this, speaker, "YOUR_GEMINI_API_KEY")

        voiceManager = VoiceManager(this) { spoken ->
            router.dispatch(spoken)
            porcupine?.start()
        }

        porcupine = PorcupineManager.Builder()
            .setAccessKey("YOUR_PICOVOICE_ACCESS_KEY")
            .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
            .setSensitivity(0.7f)
            .build(applicationContext) {
                porcupine?.stop()
                speaker.speak("Yes?")
                voiceManager?.startListening()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel("voice_chan", "Voice Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }

        val notif = NotificationCompat.Builder(this, "voice_chan")
            .setContentTitle("Voice Assistant Active")
            .setContentText("Say 'Porcupine' to trigger")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(101, notif)
        porcupine?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        porcupine?.stop()
        porcupine?.delete()
        voiceManager?.stop()
        speaker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
