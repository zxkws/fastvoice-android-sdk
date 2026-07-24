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
import com.zxkws.fastvoice.FastVoiceEvent
import com.zxkws.fastvoice.FastVoiceListener
import com.zxkws.fastvoice.OrderSnapshot

/**
 * SDK 的最小接入示例页面。
 *
 * 输入框只用于本机联调。生产 App 应从自己的安全设备凭证和定位模块提供这些值。
 */
class MainActivity : Activity() {

    private companion object {
        const val RECORD_AUDIO_REQUEST = 1001
        const val SAMPLE_ORDER_ID = "sample-order"
    }

    private lateinit var endpointInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var spotIdInput: EditText

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var interruptButton: Button
    private lateinit var startOrderButton: Button
    private lateinit var arrivalButton: Button
    private lateinit var endOrderButton: Button

    private lateinit var stateValue: TextView
    private lateinit var asrValue: TextView
    private lateinit var replyValue: TextView
    private lateinit var errorValue: TextView
    private lateinit var orderResultValue: TextView
    private lateinit var tourResultValue: TextView
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
            is FastVoiceEvent.Error -> renderError(event.error.code.orEmpty())
            is FastVoiceEvent.OrderAck ->
                runOnUiThread { orderResultValue.text = event.action }
            is FastVoiceEvent.TourAck ->
                runOnUiThread { tourResultValue.text = event.id }
            is FastVoiceEvent.PlaybackFinished ->
                runOnUiThread { playbackResultValue.text = event.tourId.orEmpty() }
            is FastVoiceEvent.PlaybackFailed ->
                runOnUiThread { playbackResultValue.text = event.code }
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
        startOrderButton.setOnClickListener { startSampleOrder() }
        arrivalButton.setOnClickListener { playSampleArrival() }
        endOrderButton.setOnClickListener { voiceClient?.endOrder(SAMPLE_ORDER_ID, 2) }
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
        if (deviceId.isBlank() || token.isBlank()) {
            renderError("device id and token are required")
            return
        }

        try {
            val config = FastVoiceConfig(
                endpoint = endpoint,
                deviceId = deviceId,
                tokenProvider = DeviceTokenProvider.fixed(token),
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

    private fun startSampleOrder() {
        val spotId = spotIdInput.text.toString().trim()
        if (spotId.isBlank()) {
            renderError("spot id is required")
            return
        }
        voiceClient?.startOrder(
            OrderSnapshot(
                id = SAMPLE_ORDER_ID,
                rev = 1,
                context = mapOf("current_spot_id" to spotId),
            ),
        )
    }

    private fun playSampleArrival() {
        val spotId = spotIdInput.text.toString().trim()
        if (spotId.isBlank()) {
            renderError("spot id is required")
            return
        }
        voiceClient?.playArrival(
            id = "sample-arrival",
            orderId = SAMPLE_ORDER_ID,
            orderRev = 1,
            spotId = spotId,
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
        deviceIdInput = input("device id (test only)")
        tokenInput = input("device token (test only)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        spotIdInput = input("spot id")

        startButton = button("Start")
        stopButton = button("Stop")
        interruptButton = button("Interrupt")
        startOrderButton = button("Start order")
        arrivalButton = button("Arrival")
        endOrderButton = button("End order")

        stateValue = output()
        asrValue = output()
        replyValue = output()
        errorValue = output()
        orderResultValue = output()
        tourResultValue = output()
        playbackResultValue = output()

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
            addView(label("Spot ID"))
            addView(spotIdInput)
            addView(buttonRow(startOrderButton, arrivalButton, endOrderButton))
            addView(rawOutput("state", stateValue, gap))
            addView(rawOutput("asr", asrValue, gap))
            addView(rawOutput("reply", replyValue, gap))
            addView(rawOutput("error", errorValue, gap))
            addView(rawOutput("orderResult", orderResultValue, gap))
            addView(rawOutput("tourResult", tourResultValue, gap))
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
