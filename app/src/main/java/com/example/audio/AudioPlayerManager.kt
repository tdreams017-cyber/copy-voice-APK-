package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
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

class AudioPlayerManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null
    private var progressJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private val _positionMs = MutableStateFlow(0)
    val positionMs: StateFlow<Int> = _positionMs.asStateFlow()

    fun play(
        audioBytes: ByteArray,
        mimeType: String,
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
        scope: CoroutineScope
    ): Result<Unit> {
        return try {
            stop()
            val ext = when {
                mimeType.contains("wav", ignoreCase = true) -> "wav"
                mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true) -> "mp3"
                mimeType.contains("ogg", ignoreCase = true) -> "ogg"
                else -> "wav"
            }
            val file = File(context.cacheDir, "tts_play_${System.currentTimeMillis()}.$ext")
            file.writeBytes(audioBytes)
            tempFile = file

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                playbackParams = PlaybackParams()
                    .setPitch(pitch.coerceIn(0.7f, 1.4f))
                    .setSpeed(speed.coerceIn(0.7f, 1.4f))
                setOnCompletionListener {
                    _isPlaying.value = false
                    _progress.value = 1f
                    progressJob?.cancel()
                }
                start()
            }
            mediaPlayer = player
            _durationMs.value = player.duration.coerceAtLeast(1)
            _positionMs.value = 0
            _progress.value = 0f
            _isPlaying.value = true

            progressJob = scope.launch(Dispatchers.Main) {
                while (isActive && _isPlaying.value) {
                    val mp = mediaPlayer ?: break
                    try {
                        if (mp.isPlaying) {
                            val dur = mp.duration.coerceAtLeast(1)
                            val pos = mp.currentPosition.coerceIn(0, dur)
                            _durationMs.value = dur
                            _positionMs.value = pos
                            _progress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                        }
                    } catch (_: Exception) {
                        break
                    }
                    delay(80L)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback failed", e)
            stop()
            Result.failure(e)
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        _isPlaying.value = false
        _progress.value = 0f
        _positionMs.value = 0
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        tempFile?.delete()
        tempFile = null
    }

    private companion object {
        const val TAG = "AudioPlayerManager"
    }
}
