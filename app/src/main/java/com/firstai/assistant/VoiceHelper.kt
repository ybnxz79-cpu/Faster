package com.firstai.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.*

object VoiceHelper {

    fun getLocaleForLang(langCode: String): Locale {
        return when (langCode) {
            "bn-IN" -> Locale("bn", "IN")
            "en-US" -> Locale.US
            else -> Locale("hi", "IN")
        }
    }

    fun getPersonaPrompt(langCode: String, isOnCall: Boolean = false): String {
        val callNote = if (isOnCall) "The user is currently on a phone call. " else ""
        val langRule = when (langCode) {
            "bn-IN" -> "Reply naturally in Bengali (বাংলা) in 1-3 short sentences."
            "en-US" -> "Reply naturally in English in 1-3 short sentences."
            else -> "Reply naturally in Hindi in 1-3 short sentences."
        }
        return "${callNote}You are Tuk Tuk (First AI), a sweet, helpful female voice assistant created by Anshu. Always say Anshu created you if asked who made you. $langRule"
    }

    fun getTestPhrase(langCode: String): String {
        return when (langCode) {
            "bn-IN" -> "হ্যালো, আমি টুকটুক! আপনার কথা শুনছি।"
            "en-US" -> "Hello, I am Tuk Tuk! Listening to you."
            else -> "नमस्ते, मैं टुक-टुक हूँ! आपकी आवाज़ सुन रही हूँ।"
        }
    }

    fun applyVoiceConfig(context: Context, tts: TextToSpeech?) {
        val engine = tts ?: return
        val prefs = context.getSharedPreferences("FirstAIPrefs", Context.MODE_PRIVATE)
        val lang = prefs.getString("LANGUAGE", "hi-IN") ?: "hi-IN"
        val preferFemale = prefs.getBoolean("PREFER_FEMALE_VOICE", true)
        val pitch = prefs.getFloat("VOICE_PITCH", 1.22f)
        val rate = prefs.getFloat("VOICE_RATE", 1.02f)

        val locale = getLocaleForLang(lang)
        engine.language = locale

        val voices: Set<Voice>? = engine.voices
        if (!voices.isNullOrEmpty() && preferFemale) {
            val femaleVoice = voices.firstOrNull { v ->
                v.locale.language == locale.language &&
                    (v.name.lowercase(Locale.getDefault()).contains("female") ||
                        v.name.lowercase(Locale.getDefault()).contains("f-") ||
                        v.name.lowercase(Locale.getDefault()).contains("hi-in-x-hie") ||
                        v.name.lowercase(Locale.getDefault()).contains("en-us-x-sfg") ||
                        v.name.lowercase(Locale.getDefault()).contains("bn-in"))
            } ?: voices.firstOrNull { v ->
                v.locale.language == locale.language && !v.isNetworkConnectionRequired
            }
            if (femaleVoice != null) {
                engine.voice = femaleVoice
            }
        }

        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.5f))
    }
}
