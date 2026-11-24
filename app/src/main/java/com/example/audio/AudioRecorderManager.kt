package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class RecordedAudioResult(
    val bytes: ByteArray,
    val mimeType: String,
    val durationSeconds: Long
)

class AudioRecorderManager(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L
    private var monitorJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    fun startRecording(scope: CoroutineScope, filePrefix: String = "rec"): Result<Unit> {
        return try {
            stopAndCleanupOnly()
            val file = File(context.cacheDir, "${filePrefix}_${System.currentTimeMillis()}.m4a")
            outputFile = file

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context.applicationContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(96_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            startTimeMs = System.currentTimeMillis()
            _isRecording.value = true
            _elapsedSeconds.value = 0L
            _amplitude.value = 0.08f

            monitorJob = scope.launch(Dispatchers.Default) {
                while (isActive && _isRecording.value) {
                    val rawAmp = try {
                        recorder?.maxAmplitude ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    val normalized = (rawAmp / 32767f).coerceIn(0.05f, 1f)
                    _amplitude.value = normalized
                    _elapsedSeconds.value = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(0L)
                    delay(70L)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder", e)
            stopAndCleanupOnly()
            Result.failure(e)
        }
    }

    fun stopRecording(): Result<RecordedAudioResult> {
        val currentRecorder = recorder
        val file = outputFile
        if (currentRecorder == null || file == null) {
            stopAndCleanupOnly()
            return Result.failure(IllegalStateException("لا يوجد تسجيل نشط."))
        }

        val elapsed = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(1L)
        return try {
            monitorJob?.cancel()
            monitorJob = null
            _isRecording.value = false
            _amplitude.value = 0f

            currentRecorder.stop()
            currentRecorder.release()
            recorder = null

            val bytes = file.readBytes()
            file.delete()
            outputFile = null

            if (bytes.isEmpty()) {
                Result.failure(IllegalStateException("لم يتم التقاط بيانات صوتية."))
            } else {
                Result.success(
                    RecordedAudioResult(
                        bytes = bytes,
                        mimeType = "audio/mp4",
                        durationSeconds = elapsed
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop MediaRecorder", e)
            stopAndCleanupOnly()
            Result.failure(IllegalStateException("التسجيل قصير جداً أو تعذر حفظه.", e))
        }
    }

    fun cancelRecording() {
        stopAndCleanupOnly()
    }

    private fun stopAndCleanupOnly() {
        monitorJob?.cancel()
        monitorJob = null
        _isRecording.value = false
        _amplitude.value = 0f
        _elapsedSeconds.value = 0L
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    private companion object {
        const val TAG = "AudioRecorderManager"
    }
}
