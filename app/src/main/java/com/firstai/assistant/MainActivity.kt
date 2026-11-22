package com.firstai.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private lateinit var tvChatOutput: TextView
    private var currentLang = "hi-IN"

    private val voiceInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                sendPromptToAI(spoken)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rbHindi = findViewById<RadioButton>(R.id.rbHindi)
        val rbEnglish = findViewById<RadioButton>(R.id.rbEnglish)
        val btnStartAI = findViewById<Button>(R.id.btnStartAI)
        tvChatOutput = findViewById(R.id.tvMainChatOutput)
        val etChatInput = findViewById<EditText>(R.id.etMainChatInput)
        val btnSend = findViewById<Button>(R.id.btnMainSend)

        // Add Bengali radio & Settings button cleanly into existing layout
        (rbHindi.parent as? RadioGroup)?.let { rg ->
            val rbBengali = RadioButton(this).apply {
                id = ViewGroup.generateViewId()
                text = "বাংলা"
                setTextColor(0xFFFFFFFF.toInt())
            }
            rg.addView(rbBengali)

            val prefs = getSharedPreferences("FirstAIPrefs", MODE_PRIVATE)
            currentLang = prefs.getString("LANGUAGE", "hi-IN") ?: "hi-IN"
            when (currentLang) {
                "bn-IN" -> rbBengali.isChecked = true
                "en-US" -> rbEnglish.isChecked = true
                else -> rbHindi.isChecked = true
            }

            rbHindi.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    currentLang = "hi-IN"
                    prefs.edit().putString("LANGUAGE", currentLang).apply()
                    VoiceHelper.applyVoiceConfig(this, tts)
                }
            }
            rbEnglish.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    currentLang = "en-US"
                    prefs.edit().putString("LANGUAGE", currentLang).apply()
                    VoiceHelper.applyVoiceConfig(this, tts)
                }
            }
            rbBengali.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    currentLang = "bn-IN"
                    prefs.edit().putString("LANGUAGE", currentLang).apply()
                    VoiceHelper.applyVoiceConfig(this, tts)
                }
            }
        }

        (btnStartAI.parent as? ViewGroup)?.let { parent ->
            val idx = parent.indexOfChild(btnStartAI)
            val btnSettings = Button(this).apply {
                text = "SETTINGS (API KEY, VOICE, LANGUAGE)"
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            }
            parent.addView(btnSettings, if (idx >= 0) idx + 1 else parent.childCount)
        }

        tvChatOutput.movementMethod = ScrollingMovementMethod()
        tts = TextToSpeech(this, this)
        requestAllPermissions()

        btnStartAI.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "Enable Display over other apps first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val serviceIntent = Intent(this, FirstAIBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "Tuk Tuk Background Voice Active", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnSend.setOnClickListener {
            val promptText = etChatInput.text.toString().trim()
            if (promptText.isEmpty()) return@setOnClickListener
            etChatInput.setText("")
            sendPromptToAI(promptText)
        }

        tvChatOutput.setOnLongClickListener {
            launchVoiceDialog()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        currentLang = getSharedPreferences("FirstAIPrefs", MODE_PRIVATE)
            .getString("LANGUAGE", "hi-IN") ?: "hi-IN"
        VoiceHelper.applyVoiceConfig(this, tts)
    }

    private fun launchVoiceDialog() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLang)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk with Tuk Tuk...")
        }
        try {
            voiceInputLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech input unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendPromptToAI(promptText: String) {
        tvChatOutput.append("\nYou: $promptText")
        val apiKey = getSharedPreferences("FirstAIPrefs", MODE_PRIVATE)
            .getString("ANY_AI_API_KEY", "")?.trim() ?: ""

        if (apiKey.isEmpty()) {
            val err = "API key missing. Tap SETTINGS and paste any AI key."
            tvChatOutput.append("\nTuk Tuk: $err")
            speakFemale(err)
            return
        }

        uiScope.launch(Dispatchers.IO) {
            val reply = try {
                val persona = VoiceHelper.getPersonaPrompt(currentLang, isOnCall = false)
                UniversalAIClient.generateReply(apiKey, persona, promptText)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

            withContext(Dispatchers.Main) {
                tvChatOutput.append("\nTuk Tuk: $reply")
                speakFemale(reply)
            }
        }
    }

    private fun speakFemale(text: String) {
        VoiceHelper.applyVoiceConfig(this, tts)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MainFemaleTTS")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            VoiceHelper.applyVoiceConfig(this, tts)
        }
    }

    private fun requestAllPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )
        ActivityCompat.requestPermissions(this, permissions, 101)
    }

    override fun onDestroy() {
        uiScope.cancel()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
