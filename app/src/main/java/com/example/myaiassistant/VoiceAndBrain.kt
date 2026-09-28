package com.example.myaiassistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

// --- Text-to-Speech Engine ---
class VoiceSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private val queue = mutableListOf<String>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isReady = true
            synchronized(queue) {
                queue.forEach { speak(it) }
                queue.clear()
            }
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!isReady) {
            synchronized(queue) { queue.add(text) }
            return
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, "AI_ID_${System.currentTimeMillis()}")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

// --- Speech Recognizer ---
class VoiceManager(private val context: Context, private val onSpoken: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null

    fun startListening() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) onSpoken(text)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { Log.e("VoiceManager", "Error: $error") }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        recognizer?.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
        recognizer?.destroy()
    }
}

// --- Central AI Intent Router ---
class ActionRouter(private val context: Context, private val speaker: VoiceSpeaker, apiKey: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val gemini = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = apiKey,
        generationConfig = generationConfig { responseMimeType = "application/json" }
    )

    fun dispatch(spokenText: String) {
        scope.launch {
            try {
                val prompt = """
                    Map this user command into JSON:
                    "$spokenText"
                    
                    Allowed schemas:
                    1. {"action": "CHECK_NOTIFICATIONS"}
                    2. {"action": "SEND_WHATSAPP", "recipient": "<phone with country code>", "message": "<body text>"}
                    3. {"action": "UNKNOWN"}
                """.trimIndent()

                val res = gemini.generateContent(prompt).text ?: return@launch
                val parsed = json.decodeFromString<VoiceAction>(res)

                when (parsed.action) {
                    "CHECK_NOTIFICATIONS" -> handleNotifications()
                    "SEND_WHATSAPP" -> {
                        val recipient = parsed.recipient ?: ""
                        val msg = parsed.message ?: ""
                        if (recipient.isNotBlank() && msg.isNotBlank()) {
                            speaker.speak("Sending WhatsApp message")
                            WhatsAppAutomationService.triggerWhatsApp(context, recipient, msg)
                        } else {
                            speaker.speak("I missed the phone number or message.")
                        }
                    }
                    else -> speaker.speak("Sorry, I don't know how to do that yet.")
                }
            } catch (e: Exception) {
                Log.e("ActionRouter", "Dispatch error: ${e.message}")
            }
        }
    }

    private suspend fun handleNotifications() {
        speaker.speak("Checking notifications")
        val db = AppDatabase.getDatabase(context)
        val unread = db.notificationDao().getUnprocessedNotifications()

        if (unread.isEmpty()) {
            speaker.speak("You have no new notifications.")
            return
        }

        val dtoList = unread.map { NotificationPromptItem(it.id, it.sourceApp, it.sender, it.body) }
        val payload = json.encodeToString(dtoList)

        val triagePrompt = """
            Triage these notifications into JSON:
            $payload
            Schema:
            {"urgentCount": Int, "summaryOverview": String, "items": [{"id": Long, "category": "URGENT"|"IGNORE", "summary": String}]}
        """.trimIndent()

        val triageRes = gemini.generateContent(triagePrompt).text ?: return
        val outcome = json.decodeFromString<TriageResponse>(triageRes)

        db.notificationDao().markAsProcessed(unread.map { it.id })

        val speech = StringBuilder(outcome.summaryOverview)
        if (outcome.urgentCount > 0) {
            speech.append(". Important: ")
            outcome.items.filter { it.category == "URGENT" }.forEach {
                speech.append(it.summary).append(". ")
            }
        }
        speaker.speak(speech.toString(), flush = false)
    }
}
