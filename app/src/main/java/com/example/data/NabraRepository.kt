package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class NabraRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("يجب تسجيل الدخول بحساب Google أولاً.")
    }

    fun observeTranscripts(userId: String): Flow<List<TranscriptNote>> = flow {
        require(userId.isNotBlank()) { "معرّف المستخدم غير صالح." }
        val path = "users/$userId/transcripts"
        emitAll(
            db.collection("users").document(userId).collection("transcripts")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        TranscriptNote::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveTranscript(
        title: String,
        rawText: String,
        organizedText: String?,
        language: String,
        formatStyle: TranscriptFormatStyle,
        durationSeconds: Long
    ): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/transcripts"
        return try {
            val uid = requireUserId()
            val cleanTitle = title.trim().ifEmpty { "نسخة صوتية" }.take(120)
            val cleanRaw = rawText.trim().take(50_000)
            require(cleanRaw.isNotEmpty()) { "النص المفرّغ فارغ." }
            val cleanOrganized = organizedText?.trim()?.take(60_000)
            val cleanLang = language.trim().ifEmpty { "ar" }.take(20)
            val safeDuration = durationSeconds.coerceIn(0L, 86_400L)

            val docRef = db.collection("users").document(uid).collection("transcripts").document()
            docPath = docRef.path

            val payload = buildMap<String, Any> {
                put("userId", uid)
                put("title", cleanTitle)
                put("rawText", cleanRaw)
                if (!cleanOrganized.isNullOrEmpty()) put("organizedText", cleanOrganized)
                put("language", cleanLang)
                put("formatStyle", formatStyle.name)
                put("durationSeconds", safeDuration)
                put("createdAt", FieldValue.serverTimestamp())
                put("updatedAt", FieldValue.serverTimestamp())
            }

            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateTranscript(
        transcriptId: String,
        title: String,
        rawText: String,
        organizedText: String?,
        formatStyle: TranscriptFormatStyle
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/transcripts/$transcriptId"
        return try {
            val uid = requireUserId()
            require(transcriptId.isNotBlank()) { "معرّف النص مطلوب للتحديث." }
            val cleanTitle = title.trim().ifEmpty { "نسخة صوتية" }.take(120)
            val cleanRaw = rawText.trim().take(50_000)
            require(cleanRaw.isNotEmpty()) { "النص المفرّغ فارغ." }
            val cleanOrganized = organizedText?.trim()?.take(60_000) ?: ""

            val docRef = db.collection("users").document(uid)
                .collection("transcripts").document(transcriptId)
            docPath = docRef.path

            docRef.update(
                mapOf(
                    "title" to cleanTitle,
                    "rawText" to cleanRaw,
                    "organizedText" to cleanOrganized,
                    "formatStyle" to formatStyle.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteTranscript(transcriptId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/transcripts/$transcriptId"
        return try {
            val uid = requireUserId()
            require(transcriptId.isNotBlank()) { "معرّف النص مطلوب للحذف." }
            val docRef = db.collection("users").document(uid)
                .collection("transcripts").document(transcriptId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeVoiceProfiles(userId: String): Flow<List<VoiceProfile>> = flow {
        require(userId.isNotBlank()) { "معرّف المستخدم غير صالح." }
        val path = "users/$userId/voiceProfiles"
        emitAll(
            db.collection("users").document(userId).collection("voiceProfiles")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        VoiceProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveVoiceProfile(
        displayName: String,
        baseVoiceName: String,
        toneDescription: String,
        dialectNotes: String,
        deliveryStyle: String,
        clonedVoiceId: String?,
        pitchShift: Double,
        speechRate: Double,
        voiceDepth: Double,
        sampleDurationSeconds: Long
    ): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/voiceProfiles"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("voiceProfiles").document()
            docPath = docRef.path

            val payload = buildMap<String, Any> {
                put("userId", uid)
                put("displayName", displayName.trim().ifEmpty { "نبرتي الشخصية" }.take(60))
                put("baseVoiceName", baseVoiceName.trim().ifEmpty { "Charon" }.take(40))
                put("toneDescription", toneDescription.trim().take(500))
                put("dialectNotes", dialectNotes.trim().take(300))
                put("deliveryStyle", deliveryStyle.trim().take(300))
                val cleanCloneId = clonedVoiceId?.trim()?.take(128)
                if (!cleanCloneId.isNullOrEmpty()) put("clonedVoiceId", cleanCloneId)
                put("pitchShift", pitchShift.coerceIn(0.7, 1.4))
                put("speechRate", speechRate.coerceIn(0.7, 1.4))
                put("voiceDepth", voiceDepth.coerceIn(-1.0, 1.0))
                put("sampleDurationSeconds", sampleDurationSeconds.coerceIn(0L, 600L))
                put("createdAt", FieldValue.serverTimestamp())
                put("updatedAt", FieldValue.serverTimestamp())
            }
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateVoiceProfileTuning(
        profileId: String,
        baseVoiceName: String,
        pitchShift: Double,
        speechRate: Double,
        voiceDepth: Double
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/voiceProfiles/$profileId"
        return try {
            val uid = requireUserId()
            require(profileId.isNotBlank()) { "معرّف بصمة الصوت غير صالح." }
            val docRef = db.collection("users").document(uid)
                .collection("voiceProfiles").document(profileId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "baseVoiceName" to baseVoiceName.trim().ifEmpty { "Charon" }.take(40),
                    "pitchShift" to pitchShift.coerceIn(0.7, 1.4),
                    "speechRate" to speechRate.coerceIn(0.7, 1.4),
                    "voiceDepth" to voiceDepth.coerceIn(-1.0, 1.0),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteVoiceProfile(profileId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/voiceProfiles/$profileId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
                .collection("voiceProfiles").document(profileId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeGeneratedSpeeches(userId: String): Flow<List<GeneratedSpeech>> = flow {
        require(userId.isNotBlank()) { "معرّف المستخدم غير صالح." }
        val path = "users/$userId/generatedSpeeches"
        emitAll(
            db.collection("users").document(userId).collection("generatedSpeeches")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(25)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        GeneratedSpeech::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveGeneratedSpeech(
        text: String,
        voiceProfileName: String,
        baseVoiceName: String,
        detectedEmotion: DetectedEmotion,
        pitchShift: Double,
        speechRate: Double,
        voiceDepth: Double
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/generatedSpeeches"
        return try {
            val uid = requireUserId()
            val cleanText = text.trim().take(5000)
            require(cleanText.isNotEmpty()) { "النص المطلوب تحويله إلى كلام فارغ." }
            val docRef = db.collection("users").document(uid).collection("generatedSpeeches").document()
            docPath = docRef.path
            docRef.set(
                mapOf(
                    "userId" to uid,
                    "text" to cleanText,
                    "voiceProfileName" to voiceProfileName.trim().ifEmpty { "نبرتي" }.take(60),
                    "baseVoiceName" to baseVoiceName.trim().ifEmpty { "Charon" }.take(40),
                    "detectedEmotion" to detectedEmotion.name,
                    "pitchShift" to pitchShift.coerceIn(0.7, 1.4),
                    "speechRate" to speechRate.coerceIn(0.7, 1.4),
                    "voiceDepth" to voiceDepth.coerceIn(-1.0, 1.0),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteGeneratedSpeech(speechId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/generatedSpeeches/$speechId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
                .collection("generatedSpeeches").document(speechId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
