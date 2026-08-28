package com.memex.android.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Interface defining audio recording capabilities with real-time amplitude and duration streams.
 */
interface AudioRecorder {
    val isRecording: StateFlow<Boolean>
    val amplitude: StateFlow<Float> // Normalized 0.0f .. 1.0f
    val durationSeconds: StateFlow<Long>
    val currentOutputFile: File?

    fun start(outputFile: File)
    fun stop(): File?
    fun cancel()
    fun release()
}

/**
 * Android implementation of [AudioRecorder] using [MediaRecorder] configured for AAC/m4a (audio/mp4).
 */
class DefaultAudioRecorder(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AudioRecorder {

    private var mediaRecorder: MediaRecorder? = null
    private var activeFile: File? = null
    private var tickerJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0L)
    override val durationSeconds: StateFlow<Long> = _durationSeconds.asStateFlow()

    override val currentOutputFile: File?
        get() = activeFile

    @Synchronized
    override fun start(outputFile: File) {
        if (_isRecording.value) {
            cancel()
        }

        activeFile = outputFile
        outputFile.parentFile?.mkdirs()

        val recorder = createMediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(outputFile.absolutePath)
        }

        try {
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            _isRecording.value = true
            _durationSeconds.value = 0L
            _amplitude.value = 0f

            startMetricsTicker()
        } catch (e: Exception) {
            try {
                recorder.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            activeFile = null
            _isRecording.value = false
            throw IOException("Failed to start audio recording: ${e.message}", e)
        }
    }

    @Synchronized
    override fun stop(): File? {
        if (!_isRecording.value) return null

        stopMetricsTicker()
        val file = activeFile

        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {}
                reset()
                release()
            }
        } catch (_: Exception) {
        } finally {
            mediaRecorder = null
            _isRecording.value = false
            _amplitude.value = 0f
        }

        return file
    }

    @Synchronized
    override fun cancel() {
        stopMetricsTicker()
        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {}
                reset()
                release()
            }
        } catch (_: Exception) {
        } finally {
            mediaRecorder = null
            _isRecording.value = false
            _amplitude.value = 0f
            _durationSeconds.value = 0L
            try {
                activeFile?.delete()
            } catch (_: Exception) {}
            activeFile = null
        }
    }

    override fun release() {
        cancel()
        scope.cancel()
    }

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun startMetricsTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            val startTimeMs = System.currentTimeMillis()
            while (isActive && _isRecording.value) {
                val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000L
                _durationSeconds.value = elapsedSec

                val rawAmp = try {
                    mediaRecorder?.maxAmplitude ?: 0
                } catch (_: Exception) {
                    0
                }
                // Normalize 0..32767 to 0.0f..1.0f
                val normalized = (rawAmp / 32767f).coerceIn(0f, 1f)
                _amplitude.value = normalized

                delay(80L)
            }
        }
    }

    private fun stopMetricsTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
