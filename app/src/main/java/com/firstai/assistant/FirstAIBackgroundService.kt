package com.firstai.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class FirstAIBackgroundService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var tvStatus: TextView
    private lateinit var tvCommandLog: TextView
    private lateinit var layoutMiniChat: View
    private lateinit var etMiniChat: EditText

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent
    private var tts: TextToSpeech? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var audioManager: AudioManager
    private var telephonyManager: TelephonyManager? = null
    private var legacyPhoneListener: PhoneStateListener? = null
    private var modernTelephonyCallback: Any? = null

    private var selectedLang = "hi-IN"
    private var apiKey = ""

    private var isMagicActive = false
    private var isInPhoneCall = false
    private var isDestroyed = false
    private var lastProcessedCommand = ""
    private var lastProcessedTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        reloadPreferences()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FirstAI::VoiceWakeLock")
        wakeLock.acquire()

        startForegroundNotification()
        setupFloatingWindow()
        setupCallStateVoiceModule()

        tts = TextToSpeech(this, this)
        setupVoiceRecognizer()
    }

    private fun reloadPreferences() {
        val prefs = getSharedPreferences("FirstAIPrefs", MODE_PRIVATE)
        selectedLang = prefs.getString("LANGUAGE", "hi-IN") ?: "hi-IN"
        apiKey = prefs.getString("ANY_AI_API_KEY", "")?.trim() ?: ""
    }

    private fun startForegroundNotification() {
        val channelId = "FirstAI_Channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "First AI & Tuk Tuk Voice",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, FirstAIBackgroundService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tuk Tuk Voice Active")
            .setContentText("Say Tuk Tuk or Jaadu to talk on home screen & calls")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cut / Stop", stopPendingIntent)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reloadPreferences()
        if (intent?.action == "ACTION_STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_mini, null)

        tvStatus = floatingView.findViewById(R.id.tvStatus)
        tvCommandLog = floatingView.findViewById(R.id.tvCommandLog)
        layoutMiniChat = floatingView.findViewById(R.id.layoutMiniChat)
        etMiniChat = floatingView.findViewById(R.id.etMiniChat)

        val btnToggleChat = floatingView.findViewById<TextView>(R.id.btnToggleChat)
        val btnCloseFloating = floatingView.findViewById<TextView>(R.id.btnCloseFloating)
        val btnMiniSend = floatingView.findViewById<Button>(R.id.btnMiniSend)

        btnToggleChat.text = "TALK"
        tvStatus.text = "Tuk Tuk • Ready"
        tvCommandLog.text = "Say Tuk Tuk / Jaadu or tap TALK..."

        btnToggleChat.setOnClickListener {
            reloadPreferences()
            val isVisible = layoutMiniChat.visibility == View.VISIBLE
            layoutMiniChat.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                isMagicActive = true
                tvStatus.text = "Tuk Tuk • Talking"
                speakFemaleVoice(
                    when (selectedLang) {
                        "bn-IN" -> "হ্যাঁ বলো, টুকটুক শুনছি!"
                        "en-US" -> "Yes, Tuk Tuk is listening!"
                        else -> "हाँ बोलो, टुक-टुक सुन रही हूँ!"
                    }
                )
            }
        }

        btnCloseFloating.setOnClickListener {
            stopSelf()
        }

        btnMiniSend.setOnClickListener {
            reloadPreferences()
            val text = etMiniChat.text.toString().trim()
            if (text.isNotEmpty()) {
                etMiniChat.setText("")
                tvCommandLog.text = "You: $text"
                processCommand(text, forceExecute = true)
            }
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 24
        params.y = 140

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (initialTouchY - event.rawY).toInt()
                    try {
                        windowManager.updateViewLayout(floatingView, params)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
    }

    private fun setupCallStateVoiceModule() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state)
                    }
                }
                modernTelephonyCallback = callback
                telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state)
                    }
                }
                legacyPhoneListener = listener
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: Exception) {}
    }

    private fun handleCallState(state: Int) {
        reloadPreferences()
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isInPhoneCall = true
                tvStatus.text = "Call Ringing • Tuk Tuk"
                val msg = when (selectedLang) {
                    "bn-IN" -> "কল আসছে! বলুন আমি কী করব?"
                    "en-US" -> "Incoming call! Tell me what to do."
                    else -> "कॉल आ रही है! बताइए मैं क्या करूँ?"
                }
                tvCommandLog.text = msg
                speakFemaleVoice(msg)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                isInPhoneCall = true
                tvStatus.text = "On Call • Tuk Tuk Ready"
                try {
                    audioManager.mode = AudioManager.MODE_IN_CALL
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                } catch (_: Exception) {}
                val msg = when (selectedLang) {
                    "bn-IN" -> "কল চলছে, টুকটুক আপনার সাথে আছে।"
                    "en-US" -> "Call active, Tuk Tuk is with you."
                    else -> "कॉल चालू है, टुक-टुक आपके साथ है।"
                }
                tvCommandLog.text = msg
                speakFemaleVoice(msg)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isInPhoneCall) {
                    isInPhoneCall = false
                    try {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        audioManager.mode = AudioManager.MODE_NORMAL
                    } catch (_: Exception) {}
                    tvStatus.text = "Tuk Tuk • Ready"
                }
            }
        }
    }

    private fun muteSystemBeep(mute: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, direction, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, direction, 0)
            }
        } catch (_: Exception) {}
    }

    private fun setupVoiceRecognizer() {
        if (isDestroyed) return
        reloadPreferences()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                muteSystemBeep(false)
                tvStatus.text = when {
                    isInPhoneCall -> "Call Voice • Listening"
                    isMagicActive -> "Tuk Tuk • Listening"
                    else -> "Home Voice • Ready"
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    tvCommandLog.text = matches[0]
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0].trim()
                    val now = System.currentTimeMillis()
                    if (command.isNotEmpty() && !(command.equals(lastProcessedCommand, true) && now - lastProcessedTime < 1500)) {
                        lastProcessedCommand = command
                        lastProcessedTime = now
                        tvCommandLog.text = command
                        processCommand(command, forceExecute = isInPhoneCall)
                    }
                }
                scheduleRestart(650L)
            }

            override fun onError(error: Int) {
                val delayMs = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 1200L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1800L
                    else -> 1500L
                }
                scheduleRestart(delayMs)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        muteSystemBeep(true)
        try {
            speechRecognizer?.startListening(speechIntent)
        } catch (_: Exception) {
            scheduleRestart(1500L)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (isDestroyed) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!isDestroyed) {
                try {
                    muteSystemBeep(true)
                    speechRecognizer?.startListening(speechIntent)
                } catch (_: Exception) {
                    setupVoiceRecognizer()
                }
            }
        }, delayMs)
    }

    private fun processCommand(rawCommand: String, forceExecute: Boolean) {
        reloadPreferences()
        val lower = rawCommand.lowercase(Locale.getDefault())

        val isTukTuk = lower.contains("tuk tuk") || lower.contains("tuktuk") || lower.contains("টুক") || lower.contains("টুকটুক") || lower.contains("टुक")
        val isJaadu = lower.contains("jaadu") || lower.contains("jadoo") || lower.contains("जादू") || lower.contains("magic")

        if (isTukTuk || isJaadu) {
            isMagicActive = true
            val wakeLabel = if (isTukTuk) "Tuk Tuk Active!" else "Jaadu Active!"
            tvStatus.text = wakeLabel
            val greeting = when (selectedLang) {
                "bn-IN" -> if (isTukTuk) "হ্যাঁ বলুন, টুকটুক বলছি! কী জানতে চান?" else "জাদু শুরু! বলুন আমি কী করব?"
                "en-US" -> if (isTukTuk) "Hey, Tuk Tuk is here! What do you want to talk about?" else "Jaadu is active! Tell me what to do."
                else -> if (isTukTuk) "हाँ जी, टुक-टुक बोल रही हूँ! क्या बात करनी है?" else "जादू शुरू हो गया! बताइए मैं क्या करूँ?"
            }
            tvCommandLog.text = greeting
            speakFemaleVoice(greeting)

            val cleaned = lower
                .replace("tuk tuk", "")
                .replace("tuktuk", "")
                .replace("টুকটুক", "")
                .replace("টুক", "")
                .replace("टुक-टुक", "")
                .replace("टुक टुक", "")
                .replace("टुक", "")
                .replace("jaadu", "")
                .replace("jadoo", "")
                .replace("जादू", "")
                .replace("magic", "")
                .trim()
            if (cleaned.isEmpty()) return
            return processCommand(cleaned, forceExecute = true)
        }

        if (lower.contains("who created") || lower.contains("who made") || lower.contains("kisne banaya") || lower.contains("किसने बनाया") || lower.contains("কে বানিয়েছে")) {
            val creatorReply = when (selectedLang) {
                "bn-IN" -> "আমাকে অংশু বানিয়েছে।"
                "en-US" -> "Anshu created me."
                else -> "मुझे अंशु ने बनाया है।"
            }
            tvCommandLog.text = creatorReply
            speakFemaleVoice(creatorReply)
            return
        }

        if (lower.startsWith("open ") || lower.contains(" open ") || lower.contains("kholo") || lower.contains("launch ") || lower.contains("खोलो") || lower.contains("ओपन") || lower.contains("খোলো")) {
            val appName = lower
                .replace("open", "")
                .replace("launch", "")
                .replace("kholo", "")
                .replace("खोलो", "")
                .replace("ओपन", "")
                .replace("খোলো", "")
                .replace("करो", "")
                .replace("app", "")
                .trim()
            openAnyApp(appName)
            return
        }

        if (lower.startsWith("call ") || lower.contains(" call ") || lower.contains("dial ") || lower.contains("कॉल") || lower.contains("কল")) {
            val contactName = lower
                .replace("call", "")
                .replace("dial", "")
                .replace("कॉल", "")
                .replace("কল", "")
                .replace("को", "")
                .replace("करो", "")
                .trim()
            makePhoneCall(contactName)
            return
        }

        if (lower.contains("website") || lower.contains("web page") || lower.contains("वेबसाइट") || lower.contains("ওয়েবসাইট")) {
            buildWebsiteAutomatically(rawCommand)
            return
        }

        if (lower.contains("chat") || lower.contains("चैट") || lower.contains("চ্যাট")) {
            layoutMiniChat.visibility = View.VISIBLE
            speakFemaleVoice(
                when (selectedLang) {
                    "bn-IN" -> "চ্যাট খুলে গেছে"
                    "en-US" -> "Chat opened"
                    else -> "चैट खुल गई है"
                }
            )
            return
        }

        if (isMagicActive || isInPhoneCall || forceExecute || lower.length > 1) {
            talkWithTukTuk(rawCommand)
        }
    }

    private fun openAnyApp(targetAppName: String) {
        if (targetAppName.isEmpty()) {
            speakFemaleVoice(
                when (selectedLang) {
                    "bn-IN" -> "কোন অ্যাপ খুলব?"
                    "en-US" -> "Which app should I open?"
                    else -> "किस ऐप का नाम लें?"
                }
            )
            return
        }

        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        for (info in resolveList) {
            val label = info.loadLabel(pm).toString()
            val labelLower = label.lowercase(Locale.getDefault())
            val pkgName = info.activityInfo.packageName

            if (labelLower.contains(targetAppName) || pkgName.lowercase(Locale.getDefault()).contains(targetAppName)) {
                val launchIntent = pm.getLaunchIntentForPackage(pkgName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    val msg = when (selectedLang) {
                        "bn-IN" -> "$label খুলছি"
                        "en-US" -> "Opening $label"
                        else -> "$label खोल रही हूँ"
                    }
                    tvCommandLog.text = msg
                    speakFemaleVoice(msg)
                    return
                }
            }
        }

        val notFound = when (selectedLang) {
            "bn-IN" -> "$targetAppName পাওয়া যায়নি"
            "en-US" -> "App not found: $targetAppName"
            else -> "$targetAppName नहीं मिला"
        }
        tvCommandLog.text = notFound
        speakFemaleVoice(notFound)
    }

    private fun makePhoneCall(contactName: String) {
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        var phoneNumber: String? = null
        var matchedName = contactName

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: ""
                if (name.lowercase(Locale.getDefault()).contains(contactName)) {
                    phoneNumber = it.getString(numIdx)
                    matchedName = name
                    break
                }
            }
        }

        if (!phoneNumber.isNullOrBlank()) {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(callIntent)
            val msg = when (selectedLang) {
                "bn-IN" -> "$matchedName-কে কল করছি"
                "en-US" -> "Calling $matchedName"
                else -> "$matchedName को कॉल कर रही हूँ"
            }
            tvCommandLog.text = msg
            speakFemaleVoice(msg)
        } else {
            val msg = when (selectedLang) {
                "bn-IN" -> "কন্ট্যাক্ট পাওয়া যায়নি"
                "en-US" -> "Contact not found"
                else -> "कॉन्टैक्ट नहीं मिला"
            }
            tvCommandLog.text = msg
            speakFemaleVoice(msg)
        }
    }

    private fun buildWebsiteAutomatically(prompt: String) {
        if (apiKey.isEmpty()) {
            val msg = "Add AI API key in Settings"
            tvCommandLog.text = msg
            speakFemaleVoice(msg)
            return
        }

        speakFemaleVoice(
            when (selectedLang) {
                "bn-IN" -> "আপনার ওয়েবসাইট বানাচ্ছি"
                "en-US" -> "Building your website"
                else -> "आपकी वेबसाइट बना रही हूँ"
            }
        )
        serviceScope.launch(Dispatchers.IO) {
            try {
                val systemPrompt = "Create a complete modern single-file HTML website with inline CSS and JavaScript. Output ONLY raw HTML without markdown fences."
                val rawResp = UniversalAIClient.generateReply(apiKey, systemPrompt, "Build a website for: $prompt")
                val htmlCode = rawResp
                    .removePrefix("```html")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val file = File(getExternalFilesDir(null), "FirstAI_Website.html")
                file.writeText(htmlCode)

                withContext(Dispatchers.Main) {
                    val msg = "Saved: ${file.name}"
                    tvCommandLog.text = msg
                    speakFemaleVoice(
                        when (selectedLang) {
                            "bn-IN" -> "ওয়েবসাইট তৈরি"
                            "en-US" -> "Website is ready"
                            else -> "वेबसाइट तैयार है"
                        }
                    )

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.fromFile(file), "text/html")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    tvCommandLog.text = "Website error"
                    speakFemaleVoice("Could not build website")
                }
            }
        }
    }

    private fun talkWithTukTuk(question: String) {
        if (apiKey.isEmpty()) {
            val msg = "Open Settings and add any AI API key"
            tvCommandLog.text = msg
            speakFemaleVoice(msg)
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val systemPrompt = VoiceHelper.getPersonaPrompt(selectedLang, isOnCall = isInPhoneCall)
                val reply = UniversalAIClient.generateReply(apiKey, systemPrompt, question).trim()

                withContext(Dispatchers.Main) {
                    if (reply.isNotEmpty()) {
                        tvCommandLog.text = reply
                        speakFemaleVoice(reply)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvCommandLog.text = "AI Error: ${e.message}"
                }
            }
        }
    }

    private fun speakFemaleVoice(text: String) {
        VoiceHelper.applyVoiceConfig(this, tts)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TukTuk_TTS")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            VoiceHelper.applyVoiceConfig(this, tts)
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        muteSystemBeep(false)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (modernTelephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager?.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                legacyPhoneListener?.let {
                    telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {}

        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (_: Exception) {}

        try {
            windowManager.removeView(floatingView)
        } catch (_: Exception) {}

        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
