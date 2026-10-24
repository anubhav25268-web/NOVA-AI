package com.nova.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs

class NovaAutomationService : AccessibilityService() {
    companion object {
        var instance: NovaAutomationService? = null
        var targetButtonToClick: String? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        targetButtonToClick?.let { target ->
            if (clickByText(root, target) || clickByText(root, "सब्सक्राइब")) {
                targetButtonToClick = null
            }
        }
    }
    fun clickByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                parent = parent.parent
            }
        }
        return false
    }
    fun unlockPhoneWithPin(pin: String) {
        swipeUpScreen()
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            for (digit in pin) { clickByText(root, digit.toString()) }
            clickByText(root, "OK"); clickByText(root, "Enter")
        }, 900)
    }
    private fun swipeUpScreen() {
        val path = Path().apply { moveTo(500f, 1600f); lineTo(500f, 400f) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        dispatchGesture(gesture, null, null)
    }
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
}

class NovaVoiceService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastVoiceRms = 0f
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val prefs = getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        val aiName = prefs.getString("custom_ai_name", "NOVA") ?: "NOVA"
        startForegroundNotification(aiName)
        handler.post { startListeningLoop() }
    }
    private fun startForegroundNotification(aiName: String) {
        val channelId = "nova_bg_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "$aiName Background", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channelId) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setContentTitle("$aiName AI ऑन है").setContentText("'Hey $aiName' बोलकर कभी भी बुलाएं").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        startForeground(1, notification)
    }
    private fun startListeningLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onRmsChanged(rmsdB: Float) { if (rmsdB > lastVoiceRms) lastVoiceRms = rmsdB }
            override fun onResults(results: Bundle?) { checkWakeCommand(results); restartWithDelay() }
            override fun onPartialResults(partialResults: Bundle?) { checkWakeCommand(partialResults) }
            override fun onError(error: Int) { restartWithDelay() }
            override fun onReadyForSpeech(params: Bundle?) { lastVoiceRms = 0f }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer?.startListening(intent)
    }
    private fun checkWakeCommand(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val heard = matches.firstOrNull()?.lowercase() ?: return
        val prefs = getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        val wakeWord = (prefs.getString("custom_ai_name", "nova") ?: "nova").lowercase().trim()
        if (heard.contains(wakeWord) || heard.contains("nova") || heard.contains("नोवा")) {
            val ownerRms = prefs.getFloat("owner_voice_rms", -1f)
            val savedPin = prefs.getString("unlock_pin", "") ?: ""
            if (ownerRms > 0f && abs(lastVoiceRms - ownerRms) > 6.5f) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "NOVA:WakeLock").acquire(5000)
            if (savedPin.isNotEmpty()) { NovaAutomationService.instance?.unlockPhoneWithPin(savedPin) }
            val cleanCmd = heard.replace("hey $wakeWord", "").replace(wakeWord, "").replace("nova", "").replace("नोवा", "").trim()
            val launchInte…
