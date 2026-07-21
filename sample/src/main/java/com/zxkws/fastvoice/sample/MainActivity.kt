package com.zxkws.fastvoice.sample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zxkws.fastvoice.DeviceTokenProvider
import com.zxkws.fastvoice.FastVoiceClient
import com.zxkws.fastvoice.FastVoiceConfig
import com.zxkws.fastvoice.FastVoiceError
import com.zxkws.fastvoice.FastVoiceEvent
import com.zxkws.fastvoice.FastVoiceListenerAdapter
import com.zxkws.fastvoice.FastVoiceState

/**
 * SDK 的最小接入示例页面。
 *
 * 输入框只用于本机联调。生产 App 应从自己的安全设备凭证和定位模块提供这些值。
 */
class MainActivity : Activity() {

    private companion object {
        const val RECORD_AUDIO_REQUEST = 1001
    }

    private lateinit var endpointInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var trustedMessageInput: EditText

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var interruptButton: Button
    private lateinit var trustedMessageButton: Button

    private lateinit var stateValue: TextView
    private lateinit var asrValue: TextView
    private lateinit var replyValue: TextView
    private lateinit var errorValue: TextView
    private lateinit var wakeWordsValue: TextView
    private lateinit var contextResultValue: TextView
    private lateinit var arrivalResultValue: TextView

    private var voiceClient: FastVoiceClient? = null

    private val voiceListener = object : FastVoiceListenerAdapter() {
        override fun onStateChanged(state: FastVoiceState) {
            renderState(state.value)
        }

        override fun onAsr(text: String) {
            renderAsr(text)
        }

        override fun onReplyDelta(text: String) {
            renderReplyDelta(text)
        }

        override fun onError(error: FastVoiceError) {
            renderError(error.code.orEmpty())
        }

        override fun onContextUpdated(event: FastVoiceEvent.ContextUpdated) {
            runOnUiThread { contextResultValue.text = event.version.toString() }
        }

        override fun onArrivalAccepted(event: FastVoiceEvent.ArrivalAccepted) {
            runOnUiThread { arrivalResultValue.text = event.eventId }
        }

        override fun onArrivalRejected(event: FastVoiceEvent.ArrivalRejected) {
            runOnUiThread { arrivalResultValue.text = event.code.orEmpty() }
        }

        override fun onActiveWakeWords(words: List<String>) {
            runOnUiThread { wakeWordsValue.text = words.toString() }
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
        trustedMessageButton.setOnClickListener { sendTrustedMessage() }
    }

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermissionIfNeeded()
            return
        }

        val endpoint = endpointInput.text.toString().trim()
        val deviceId = deviceIdInput.text.toString().trim()
        val token = tokenInput.text.toString()
        if (endpoint.isBlank()) {
            renderError("endpoint is required")
            return
        }
        if (deviceId.isBlank() != token.isBlank()) {
            renderError("device id and token must be provided together")
            return
        }

        try {
            val config = FastVoiceConfig(
                endpoint = endpoint,
                deviceId = deviceId.ifBlank { null },
                tokenProvider = token.takeIf { it.isNotBlank() }
                    ?.let { DeviceTokenProvider.fixed(it) },
                allowInsecureConnection = endpoint.startsWith("ws://", ignoreCase = true),
                bypassSystemProxy = endpoint.startsWith("ws://127.0.0.1", ignoreCase = true),
            )
            voiceClient?.close()
            voiceClient = FastVoiceClient(applicationContext, config, voiceListener)
            voiceClient?.start()
        } catch (error: Exception) {
            renderError(error.message.orEmpty())
        }
    }

    private fun sendTrustedMessage() {
        voiceClient?.sendTrustedMessage(trustedMessageInput.text.toString())
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

    /** 以下方法不改写回调值；reply_delta 只按收到顺序直接追加。 */
    private fun renderState(rawValue: String) {
        runOnUiThread { stateValue.text = rawValue }
    }

    private fun renderAsr(rawValue: String) {
        runOnUiThread { asrValue.text = rawValue }
    }

    private fun renderReplyDelta(rawValue: String) {
        runOnUiThread { replyValue.append(rawValue) }
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
        deviceIdInput = input("device id (test only)")
        tokenInput = input("device token (test only)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        trustedMessageInput = input("vehicle-platform-signed JSON").apply {
            isSingleLine = false
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        startButton = button("Start")
        stopButton = button("Stop")
        interruptButton = button("Interrupt")
        trustedMessageButton = button("Send signed message")

        stateValue = output()
        asrValue = output()
        replyValue = output()
        errorValue = output()
        wakeWordsValue = output()
        contextResultValue = output()
        arrivalResultValue = output()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title("FastVoice SDK sample"))
            addView(label("Endpoint"))
            addView(endpointInput)
            addView(label("Device ID"))
            addView(deviceIdInput)
            addView(label("Token"))
            addView(tokenInput)
            addView(buttonRow(startButton, stopButton, interruptButton))
            addView(label("Signed context / arrival JSON (test only)"))
            addView(trustedMessageInput)
            addView(trustedMessageButton)
            addView(rawOutput("state", stateValue, gap))
            addView(rawOutput("asr", asrValue, gap))
            addView(rawOutput("reply", replyValue, gap))
            addView(rawOutput("error", errorValue, gap))
            addView(rawOutput("activeWakeWords", wakeWordsValue, gap))
            addView(rawOutput("contextResult", contextResultValue, gap))
            addView(rawOutput("arrivalResult", arrivalResultValue, gap))
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
