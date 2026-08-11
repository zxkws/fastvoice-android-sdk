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
import com.zxkws.fastvoice.FastVoiceClient
import com.zxkws.fastvoice.FastVoiceConfig
import com.zxkws.fastvoice.FastVoiceEvent
import com.zxkws.fastvoice.FastVoiceListener
import com.zxkws.fastvoice.FastVoiceLogger
import org.json.JSONObject

/**
 * SDK 的最小接入示例页面。
 *
 * 输入框只用于本机联调。生产 App 应从自己的异步定位模块提供经纬度 JSON。
 */
class MainActivity : Activity() {

    private companion object {
        const val RECORD_AUDIO_REQUEST = 1001
    }

    private lateinit var endpointInput: EditText
    private lateinit var parkInput: EditText
    private lateinit var spotInput: EditText
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var interruptButton: Button
    private lateinit var updateLocationButton: Button
    private lateinit var welcomeButton: Button
    private lateinit var clearLocationButton: Button

    private lateinit var stateValue: TextView
    private lateinit var asrValue: TextView
    private lateinit var replyValue: TextView
    private lateinit var errorValue: TextView
    private lateinit var locationResultValue: TextView
    private lateinit var welcomeResultValue: TextView
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
            is FastVoiceEvent.LocationAck ->
                runOnUiThread { locationResultValue.text = event.toString() }
            is FastVoiceEvent.WelcomeAck ->
                runOnUiThread { welcomeResultValue.text = event.toString() }
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
        updateLocationButton.setOnClickListener { updateSampleLocation() }
        welcomeButton.setOnClickListener { playSampleWelcome() }
        clearLocationButton.setOnClickListener { voiceClient?.clearLocation() }
    }

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermissionIfNeeded()
            return
        }

        val endpoint = endpointInput.text.toString().trim()
        if (endpoint.isBlank()) {
            renderError("endpoint is required")
            return
        }

        try {
            val config = FastVoiceConfig(
                endpoint = endpoint,
                getLocation = { callback -> getSampleLocationAsync(callback) },
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

    private fun updateSampleLocation() {
        val park = parkInput.text.toString()
        val spot = spotInput.text.toString().ifBlank { null }
        if (park.isBlank()) {
            renderError("park is required")
            return
        }
        voiceClient?.updateLocation(park, spot)
    }

    private fun getSampleLocationAsync(callback: (JSONObject?) -> Unit) {
        val latitude = latitudeInput.text.toString().toDoubleOrNull()
        val longitude = longitudeInput.text.toString().toDoubleOrNull()
        callback(
            if (latitude == null || longitude == null) null else JSONObject()
                .put("latitude", latitude)
                .put("longitude", longitude),
        )
    }

    private fun playSampleWelcome() {
        val park = parkInput.text.toString()
        val spot = spotInput.text.toString().ifBlank { null }
        if (park.isBlank()) {
            renderError("park is required")
            return
        }
        voiceClient?.playWelcome(park, spot)
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
        parkInput = input("park").apply { setText("南苑森林湿地公园") }
        spotInput = input("spot (optional)")
        latitudeInput = input("latitude").apply {
            setText("39.81")
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        longitudeInput = input("longitude").apply {
            setText("116.37")
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        startButton = button("Start")
        stopButton = button("Stop")
        interruptButton = button("Interrupt")
        updateLocationButton = button("Update location")
        welcomeButton = button("Play welcome")
        clearLocationButton = button("Clear location")

        stateValue = output()
        asrValue = output()
        replyValue = output()
        errorValue = output()
        locationResultValue = output()
        welcomeResultValue = output()
        playbackResultValue = output()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title("FastVoice SDK sample"))
            addView(label("Endpoint"))
            addView(endpointInput)
            addView(buttonRow(startButton, stopButton, interruptButton))
            addView(label("Latitude / Longitude"))
            addView(latitudeInput)
            addView(longitudeInput)
            addView(label("Park"))
            addView(parkInput)
            addView(label("Spot"))
            addView(spotInput)
            addView(buttonRow(updateLocationButton, welcomeButton, clearLocationButton))
            addView(rawOutput("state", stateValue, gap))
            addView(rawOutput("asr", asrValue, gap))
            addView(rawOutput("reply", replyValue, gap))
            addView(rawOutput("error", errorValue, gap))
            addView(rawOutput("locationResult", locationResultValue, gap))
            addView(rawOutput("welcomeResult", welcomeResultValue, gap))
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
