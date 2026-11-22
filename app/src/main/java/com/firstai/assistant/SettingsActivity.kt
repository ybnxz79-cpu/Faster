package com.firstai.assistant

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private lateinit var etApiKey: EditText
    private lateinit var rgLang: RadioGroup
    private lateinit var rbHindi: RadioButton
    private lateinit var rbEnglish: RadioButton
    private lateinit var rbBengali: RadioButton
    private lateinit var swFemale: Switch
    private lateinit var sbPitch: SeekBar
    private lateinit var sbRate: SeekBar
    private lateinit var tvPitchLabel: TextView
    private lateinit var tvRateLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etApiKey = findViewById(R.id.etSettingsApiKey)
        val cbShowKey = findViewById<CheckBox>(R.id.cbShowApiKey)
        rgLang = findViewById(R.id.rgSettingsLang)
        rbHindi = findViewById(R.id.rbLangHindi)
        rbEnglish = findViewById(R.id.rbLangEnglish)
        rbBengali = findViewById(R.id.rbLangBengali)
        swFemale = findViewById(R.id.swPreferFemaleVoice)
        sbPitch = findViewById(R.id.sbVoicePitch)
        sbRate = findViewById(R.id.sbVoiceRate)
        tvPitchLabel = findViewById(R.id.tvPitchLabel)
        tvRateLabel = findViewById(R.id.tvRateLabel)
        val btnTest = findViewById<Button>(R.id.btnTestFemaleVoice)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        tts = TextToSpeech(this, this)

        val prefs = getSharedPreferences("FirstAIPrefs", MODE_PRIVATE)
        val savedKey = prefs.getString("ANY_AI_API_KEY", "") ?: ""
        val savedLang = prefs.getString("LANGUAGE", "hi-IN") ?: "hi-IN"
        val preferFemale = prefs.getBoolean("PREFER_FEMALE_VOICE", true)
        val pitch = prefs.getFloat("VOICE_PITCH", 1.22f)
        val rate = prefs.getFloat("VOICE_RATE", 1.02f)

        etApiKey.setText(savedKey)
        when (savedLang) {
            "bn-IN" -> rbBengali.isChecked = true
            "en-US" -> rbEnglish.isChecked = true
            else -> rbHindi.isChecked = true
        }
        swFemale.isChecked = preferFemale

        val pitchProgress = ((pitch - 0.5f) * 100).toInt().coerceIn(0, 120)
        val rateProgress = ((rate - 0.5f) * 100).toInt().coerceIn(0, 150)
        sbPitch.progress = pitchProgress
        sbRate.progress = rateProgress
        tvPitchLabel.text = "Voice Pitch: ${String.format("%.2f", pitch)}"
        tvRateLabel.text = "Speaking Speed: ${String.format("%.2f", rate)}"

        cbShowKey.setOnCheckedChangeListener { _, isChecked ->
            etApiKey.inputType = if (isChecked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etApiKey.setSelection(etApiKey.text.length)
        }

        sbPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = 0.5f + (progress / 100f)
                tvPitchLabel.text = "Voice Pitch: ${String.format("%.2f", p)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val r = 0.5f + (progress / 100f)
                tvRateLabel.text = "Speaking Speed: ${String.format("%.2f", r)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnTest.setOnClickListener {
            saveCurrentValues()
            val lang = getSelectedLangCode()
            VoiceHelper.applyVoiceConfig(this, tts)
            tts?.speak(VoiceHelper.getTestPhrase(lang), TextToSpeech.QUEUE_FLUSH, null, "TestVoice")
        }

        btnSave.setOnClickListener {
            saveCurrentValues()
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun getSelectedLangCode(): String {
        return when (rgLang.checkedRadioButtonId) {
            R.id.rbLangBengali -> "bn-IN"
            R.id.rbLangEnglish -> "en-US"
            else -> "hi-IN"
        }
    }

    private fun saveCurrentValues() {
        val key = etApiKey.text.toString().trim()
        val lang = getSelectedLangCode()
        val pitch = (0.5f + (sbPitch.progress / 100f)).coerceIn(0.5f, 2.0f)
        val rate = (0.5f + (sbRate.progress / 100f)).coerceIn(0.5f, 2.5f)

        getSharedPreferences("FirstAIPrefs", MODE_PRIVATE).edit()
            .putString("ANY_AI_API_KEY", key)
            .putString("LANGUAGE", lang)
            .putBoolean("PREFER_FEMALE_VOICE", swFemale.isChecked)
            .putFloat("VOICE_PITCH", pitch)
            .putFloat("VOICE_RATE", rate)
            .apply()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            VoiceHelper.applyVoiceConfig(this, tts)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
