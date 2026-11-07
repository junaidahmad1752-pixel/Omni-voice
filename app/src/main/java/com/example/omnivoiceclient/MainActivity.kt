package com.example.omnivoiceclient

import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var voice: EditText
    private lateinit var inputText: EditText
    private lateinit var speakButton: Button
    private lateinit var stopButton: Button
    private lateinit var status: TextView

    private var player: MediaPlayer? = null

    // Long read timeout: on a CPU-only server, synthesis can take a while.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverUrl = findViewById(R.id.serverUrl)
        apiKey = findViewById(R.id.apiKey)
        voice = findViewById(R.id.voice)
        inputText = findViewById(R.id.inputText)
        speakButton = findViewById(R.id.speakButton)
        stopButton = findViewById(R.id.stopButton)
        status = findViewById(R.id.status)

        serverUrl.setText(prefs.getString("url", ""))
        apiKey.setText(prefs.getString("key", ""))
        voice.setText(prefs.getString("voice", ""))

        speakButton.setOnClickListener { speak() }
        stopButton.setOnClickListener {
            stopPlayback()
            setStatus("Stopped")
        }
    }

    private fun speak() {
        val base = serverUrl.text.toString().trim().trimEnd('/')
        val key = apiKey.text.toString().trim()
        val voiceName = voice.text.toString().trim()
        val text = inputText.text.toString().trim()

        if (base.isEmpty() || !(base.startsWith("http://") || base.startsWith("https://"))) {
            setStatus("Enter a valid server URL (starting with http:// or https://)")
            return
        }
        if (text.isEmpty()) {
            setStatus("Enter some text first")
            return
        }

        prefs.edit()
            .putString("url", base)
            .putString("key", key)
            .putString("voice", voiceName)
            .apply()

        stopPlayback()
        speakButton.isEnabled = false
        setStatus("Generating speech...")

        Thread {
            try {
                val json = JSONObject().apply {
                    put("model", "omnivoice")
                    put("input", text)
                    put("response_format", "wav")
                    if (voiceName.isNotEmpty()) put("voice", voiceName)
                }

                val builder = Request.Builder()
                    .url("$base/v1/audio/speech")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")

                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.take(300) ?: ""
                        runOnUiThread { setStatus("Server error ${response.code}: $err") }
                        return@use
                    }
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        runOnUiThread { setStatus("Server returned empty audio") }
                        return@use
                    }
                    val file = File(cacheDir, "speech.wav")
                    file.writeBytes(bytes)
                    runOnUiThread { play(file) }
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Failed: ${e.message}") }
            } finally {
                runOnUiThread { speakButton.isEnabled = true }
            }
        }.start()
    }

    private fun play(file: File) {
        try {
            stopPlayback()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { setStatus("Done") }
                setOnErrorListener { _, what, extra ->
                    setStatus("Playback error ($what, $extra)")
                    true
                }
                prepare()
                start()
            }
            setStatus("Playing...")
        } catch (e: Exception) {
            setStatus("Playback failed: ${e.message}")
        }
    }

    private fun stopPlayback() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
    }

    private fun setStatus(msg: String) {
        status.text = msg
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}
