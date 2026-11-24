package com.example.ai

import com.example.audio.AudioDspProcessor
import com.example.data.DetectedEmotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VoiceCloneApiClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun cloneVoice(
        apiKey: String,
        voiceName: String,
        dialectAndToneDescription: String,
        audioBytes: ByteArray,
        mimeType: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotEmpty()) { "مفتاح API غير متوفر." }
            require(audioBytes.isNotEmpty()) { "الملف الصوتي فارغ." }

            val ext = when {
                mimeType.contains("wav", ignoreCase = true) -> "wav"
                mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true) -> "mp3"
                mimeType.contains("ogg", ignoreCase = true) -> "ogg"
                else -> "m4a"
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", voiceName.ifBlank { "Nabra Cloned Voice" }.take(60))
                .addFormDataPart("description", dialectAndToneDescription.take(300))
                .addFormDataPart(
                    "files",
                    "voice_sample.$ext",
                    audioBytes.toRequestBody(mimeType.toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/voices/add")
                .header("xi-api-key", cleanKey)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        JSONObject(bodyString).optJSONObject("detail")?.optString("message")
                            ?: JSONObject(bodyString).optString("detail")
                    }.getOrNull().orEmpty()
                    return@withContext Result.failure(
                        IllegalStateException(
                            "فشل استنساخ الصوت عبر API (${response.code}): ${detail.ifBlank { bodyString.take(140) }}"
                        )
                    )
                }
                val voiceId = JSONObject(bodyString).optString("voice_id").trim()
                if (voiceId.isEmpty()) {
                    Result.failure(IllegalStateException("لم يرجع الخادم معرّف الصوت المستنسخ."))
                } else {
                    Result.success(voiceId)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun synthesizeWithClonedVoice(
        apiKey: String,
        voiceId: String,
        text: String,
        emotion: DetectedEmotion,
        intensity: Double
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotEmpty()) { "مفتاح API غير متوفر." }
            require(voiceId.isNotBlank()) { "معرّف الصوت المستنسخ مفقود." }

            val (stability, similarity, styleBoost) = when (emotion) {
                DetectedEmotion.HAPPY, DetectedEmotion.EXCITED ->
                    Triple(0.38, 0.85, (0.45 + intensity * 0.4).coerceIn(0.0, 1.0))
                DetectedEmotion.ANGRY ->
                    Triple(0.32, 0.82, (0.55 + intensity * 0.4).coerceIn(0.0, 1.0))
                DetectedEmotion.SAD, DetectedEmotion.CALM ->
                    Triple(0.68, 0.88, (0.20 + intensity * 0.25).coerceIn(0.0, 1.0))
                DetectedEmotion.SERIOUS ->
                    Triple(0.62, 0.86, 0.25)
                DetectedEmotion.QUESTIONING ->
                    Triple(0.45, 0.85, 0.35)
                DetectedEmotion.NEUTRAL ->
                    Triple(0.52, 0.85, 0.20)
            }

            val jsonBody = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put(
                    "voice_settings",
                    JSONObject().apply {
                        put("stability", stability)
                        put("similarity_boost", similarity)
                        put("style", styleBoost)
                        put("use_speaker_boost", true)
                    }
                )
            }

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=pcm_24000")
                .header("xi-api-key", cleanKey)
                .header("Content-Type", "application/json")
                .header("Accept", "audio/pcm")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IllegalStateException(
                            "فشل توليد الصوت المستنسخ (${response.code}): ${errBody.take(150)}"
                        )
                    )
                }
                val pcmBytes = response.body?.bytes() ?: ByteArray(0)
                if (pcmBytes.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("أعاد خادم الاستنساخ ملفاً فارغاً."))
                }
                val wavBytes = AudioDspProcessor.wrapPcm16MonoAsWav(pcmBytes, 24_000)
                Result.success(wavBytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
