package com.zxkws.fastvoice.sample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zxkws.fastvoice.DeviceTokenProvider
import com.zxkws.fastvoice.ContentRequest
import com.zxkws.fastvoice.FastVoiceClient
import com.zxkws.fastvoice.FastVoiceConfig
import com.zxkws.fastvoice.FastVoiceEvent
import com.zxkws.fastvoice.FastVoiceListener
import com.zxkws.fastvoice.FastVoiceLogger
import com.zxkws.fastvoice.SessionRef
import com.zxkws.fastvoice.SessionSnapshot

/**
 * SDK 的最小接入示例页面。
 *
 * 输入框只用于本机联调。生产 App 应从自己的安全设备凭证和定位模块提供这些值。
 */
class MainActivity : Activity() {

    private companion object {
        const val RECORD_AUDIO_REQUEST = 1001
        const val SAMPLE_SESSION_ID = "sample-session"
    }

    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var attributeInput: EditText
    private lateinit var contentKeyInput: EditText

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var interruptButton: Button
    private lateinit var startSessionButton: Button
    private lateinit var contentButton: Button
    private lateinit var endSessionButton: Button

    private lateinit var stateValue: TextView
    private lateinit var asrValue: TextView
    private lateinit var replyValue: TextView
    private lateinit var errorValue: TextView
    private lateinit var sessionResultValue: TextView
    private lateinit var contentResultValue: TextView
    private lateinit var playbackResultValue: TextView

    private var voiceClient: FastVoiceClient? = null

    private val voiceListener = FastVoiceListener { event ->
        when (event) {
            is FastVoiceEvent.StateChanged -> renderState(event.state.value)
            is FastVoiceEvent.Transcript -> {
                if (event.role == "user") {
                    renderAsr(event.text)
                } else {
                    renderReply(event.text)
                }
            }
            is FastVoiceEvent.Error -> renderError(event.error.toString())
            is FastVoiceEvent.SessionAck ->
                runOnUiThread { sessionResultValue.text = event.toString() }
            is FastVoiceEvent.ContentAck ->
                runOnUiThread { contentResultValue.text = event.toString() }
            is FastVoiceEvent.PlaybackFinished ->
                runOnUiThread { playbackResultValue.text = event.toString() }
            is FastVoiceEvent.PlaybackFailed ->
                runOnUiThread { playbackResultValue.text = event.toString() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        bindSdk()
        requestMicrophonePermissionIfNeeded()
    }

    private fun bindSdk() {
        startButton.setOnClickListener { startVoice() }
        stopButton.setOnClickListener { voiceClient?.stop() }
        interruptButton.setOnClickListener { voiceClient?.interrupt() }
        startSessionButton.setOnClickListener { startSampleSession() }
        contentButton.setOnClickListener { playSampleContent() }
        endSessionButton.setOnClickListener { voiceClient?.endSession(SAMPLE_SESSION_ID, 2) }
    }

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermissionIfNeeded()
            return
        }

        val endpoint = endpointInput.text.toString().trim()
        val token = tokenInput.text.toString()
        if (endpoint.isBlank()) {
            renderError("endpoint is required")
            return
        }
        if (token.isBlank()) {
            renderError("token is required")
            return
        }

        try {
            val config = FastVoiceConfig(
                endpoint = endpoint,
                tokenProvider = DeviceTokenProvider.fixed(token),
                allowInsecureConnection = endpoint.startsWith("ws://", ignoreCase = true),
                bypassSystemProxy = endpoint.startsWith("ws://127.0.0.1", ignoreCase = true),
                logger = FastVoiceLogger { level, message, error ->
                    Log.d("FastVoiceSample", "$level $message", error)
                },
            )
            voiceClient?.close()
            voiceClient = FastVoiceClient(applicationContext, config, voiceListener)
            voiceClient?.start()
        } catch (error: Exception) {
            renderError(error.message.orEmpty())
        }
    }

    private fun startSampleSession() {
        val attribute = attributeInput.text.toString()
        if (attribute.isBlank()) {
            renderError("attribute value is required")
            return
        }
        voiceClient?.startSession(
            SessionSnapshot(
                id = SAMPLE_SESSION_ID,
                rev = 1,
                attributes = mapOf("sample_value" to attribute),
            ),
        )
    }

    private fun playSampleContent() {
        val key = contentKeyInput.text.toString().trim()
        if (key.isBlank()) {
            renderError("content key is required")
            return
        }
        voiceClient?.playContent(
            ContentRequest(
                id = "sample-content",
                key = key,
                session = SessionRef(SAMPLE_SESSION_ID, 1),
                attributes = emptyMap(),
            ),
        )
    }

    private fun requestMicrophonePermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            renderError("android.permission.RECORD_AUDIO denied")
        }
    }

    override fun onDestroy() {
        voiceClient?.close()
        voiceClient = null
        super.onDestroy()
    }

    override fun onStop() {
        voiceClient?.stop()
        super.onStop()
    }

    /** 以下方法直接展示回调原值。 */
    private fun renderState(rawValue: String) {
        runOnUiThread { stateValue.text = rawValue }
    }

    private fun renderAsr(rawValue: String) {
        runOnUiThread { asrValue.text = rawValue }
    }

    private fun renderReply(rawValue: String) {
        runOnUiThread { replyValue.text = rawValue }
    }

    private fun renderError(rawValue: String) {
        runOnUiThread { errorValue.text = rawValue }
    }

    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val gap = (8 * density).toInt()

        endpointInput = input("wss://your-server.example/ws").apply {
            setText("ws://127.0.0.1:8100/ws")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        tokenInput = input("device token (test only)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        attributeInput = input("sample attribute value")
        contentKeyInput = input("content key")

        startButton = button("Start")
        stopButton = button("Stop")
        interruptButton = button("Interrupt")
        startSessionButton = button("Start session")
        contentButton = button("Play content")
        endSessionButton = button("End session")

        stateValue = output()
        asrValue = output()
        replyValue = output()
        errorValue = output()
        sessionResultValue = output()
        contentResultValue = output()
        playbackResultValue = output()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title("FastVoice SDK sample"))
            addView(label("Endpoint"))
            addView(endpointInput)
            addView(label("Token"))
            addView(tokenInput)
            addView(buttonRow(startButton, stopButton, interruptButton))
            addView(label("Session attribute"))
            addView(attributeInput)
            addView(label("Content key"))
            addView(contentKeyInput)
            addView(buttonRow(startSessionButton, contentButton, endSessionButton))
            addView(rawOutput("state", stateValue, gap))
            addView(rawOutput("asr", asrValue, gap))
            addView(rawOutput("reply", replyValue, gap))
            addView(rawOutput("error", errorValue, gap))
            addView(rawOutput("sessionResult", sessionResultValue, gap))
            addView(rawOutput("contentResult", contentResultValue, gap))
            addView(rawOutput("playbackResult", playbackResultValue, gap))
        }

        return ScrollView(this).apply { addView(content) }
    }

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        isSingleLine = true
    }

    private fun button(textValue: String) = Button(this).apply {
        text = textValue
        isAllCaps = false
    }

    private fun buttonRow(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        buttons.forEach { button ->
            addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun title(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 24f
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 14f
    }

    private fun output() = TextView(this).apply {
        setTextIsSelectable(true)
        textSize = 16f
    }

    private fun rawOutput(label: String, value: TextView, topMargin: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
            })
            addView(value)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, topMargin, 0, 0) }
        }

}
