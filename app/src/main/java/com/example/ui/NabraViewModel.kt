package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.NabraAiService
import com.example.ai.SynthesizedAudio
import com.example.audio.AudioPlayerManager
import com.example.audio.AudioRecorderManager
import com.example.data.AudioLanguageOption
import com.example.data.GeneratedSpeech
import com.example.data.NabraRepository
import com.example.data.TranscriptFormatStyle
import com.example.data.TranscriptNote
import com.example.data.UiState
import com.example.data.VoiceProfile
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranscribeDraftState(
    val selectedLanguage: AudioLanguageOption = AudioLanguageOption.ARABIC,
    val title: String = "",
    val rawText: String = "",
    val organizedText: String = "",
    val formatStyle: TranscriptFormatStyle = TranscriptFormatStyle.CLEAN_PARAGRAPHS,
    val durationSeconds: Long = 0L,
    val savedNoteId: String? = null,
    val isTranscribing: Boolean = false,
    val isOrganizing: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

data class VoiceStudioUiState(
    val isRecordingSample: Boolean = false,
    val isAnalyzingSample: Boolean = false,
    val isSynthesizing: Boolean = false,
    val selectedProfileId: String? = null,
    val profileNameDraft: String = "نبرتي الشخصية",
    val baseVoiceName: String = "Charon",
    val toneDescription: String = "",
    val deliveryStyle: String = "واضح ومتزن",
    val pitchShift: Double = 1.0,
    val speechRate: Double = 1.0,
    val ttsText: String = "",
    val lastSynthesized: SynthesizedAudio? = null,
    val errorMessage: String? = null
)

class NabraViewModel(
    private val repository: NabraRepository,
    private val aiService: NabraAiService,
    private val recorder: AudioRecorderManager,
    private val player: AudioPlayerManager,
    private val currentUserId: String
) : ViewModel() {

    val isRecordingMic: StateFlow<Boolean> = recorder.isRecording
    val recordingAmplitude: StateFlow<Float> = recorder.amplitude
    val recordingElapsedSeconds: StateFlow<Long> = recorder.elapsedSeconds

    val isPlayingAudio: StateFlow<Boolean> = player.isPlaying
    val playbackProgress: StateFlow<Float> = player.progress
    val playbackDurationMs: StateFlow<Int> = player.durationMs
    val playbackPositionMs: StateFlow<Int> = player.positionMs

    private val _transcribeDraft = MutableStateFlow(TranscribeDraftState())
    val transcribeDraft: StateFlow<TranscribeDraftState> = _transcribeDraft.asStateFlow()

    private val _voiceStudio = MutableStateFlow(VoiceStudioUiState())
    val voiceStudio: StateFlow<VoiceStudioUiState> = _voiceStudio.asStateFlow()

    private val _statusMessages = Channel<String>(Channel.BUFFERED)
    val statusMessages = _statusMessages.receiveAsFlow()

    val transcriptsState: StateFlow<UiState<List<TranscriptNote>>> =
        repository.observeTranscripts(currentUserId)
            .map<List<TranscriptNote>, UiState<List<TranscriptNote>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing transcripts", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "تعذر تحميل النصوص المحفوظة."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val voiceProfilesState: StateFlow<UiState<List<VoiceProfile>>> =
        repository.observeVoiceProfiles(currentUserId)
            .map<List<VoiceProfile>, UiState<List<VoiceProfile>>> { profiles ->
                if (profiles.isNotEmpty() && _voiceStudio.value.selectedProfileId == null) {
                    val first = profiles.first()
                    _voiceStudio.update { current ->
                        current.copy(
                            selectedProfileId = first.id,
                            profileNameDraft = first.displayName,
                            baseVoiceName = first.baseVoiceName,
                            toneDescription = first.toneDescription,
                            deliveryStyle = first.deliveryStyle,
                            pitchShift = first.pitchShift,
                            speechRate = first.speechRate
                        )
                    }
                }
                UiState.Success(profiles)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing voice profiles", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "تعذر تحميل بصمات الصوت."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val generatedSpeechesState: StateFlow<UiState<List<GeneratedSpeech>>> =
        repository.observeGeneratedSpeeches(currentUserId)
            .map<List<GeneratedSpeech>, UiState<List<GeneratedSpeech>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing generated speeches", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "تعذر تحميل سجل التوليد الصوتي."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    // --- Transcription Actions ---

    fun setLanguage(option: AudioLanguageOption) {
        _transcribeDraft.update { it.copy(selectedLanguage = option) }
    }

    fun updateDraftTitle(title: String) {
        _transcribeDraft.update { it.copy(title = title.take(120)) }
    }

    fun updateDraftRawText(text: String) {
        _transcribeDraft.update { it.copy(rawText = text.take(50_000)) }
    }

    fun updateDraftOrganizedText(text: String) {
        _transcribeDraft.update { it.copy(organizedText = text.take(60_000)) }
    }

    fun setFormatStyle(style: TranscriptFormatStyle) {
        _transcribeDraft.update { it.copy(formatStyle = style) }
    }

    fun startTranscribeRecording() {
        if (_voiceStudio.value.isRecordingSample) {
            recorder.cancelRecording()
            _voiceStudio.update { it.copy(isRecordingSample = false) }
        }
        player.stop()
        _transcribeDraft.update { it.copy(errorMessage = null) }
        val result = recorder.startRecording(viewModelScope, filePrefix = "transcribe")
        if (result.isFailure) {
            _transcribeDraft.update {
                it.copy(errorMessage = result.exceptionOrNull()?.localizedMessage ?: "تعذر بدء التسجيل من الميكروفون.")
            }
        }
    }

    fun stopAndTranscribeRecording() {
        val stopResult = recorder.stopRecording()
        val recorded = stopResult.getOrElse { err ->
            _transcribeDraft.update {
                it.copy(errorMessage = err.localizedMessage ?: "تعذر إيقاف التسجيل.")
            }
            return
        }
        runTranscription(
            audioBytes = recorded.bytes,
            mimeType = recorded.mimeType,
            durationSeconds = recorded.durationSeconds
        )
    }

    fun cancelTranscribeRecording() {
        recorder.cancelRecording()
    }

    fun transcribeImportedAudio(audioBytes: ByteArray, mimeType: String) {
        runTranscription(
            audioBytes = audioBytes,
            mimeType = mimeType,
            durationSeconds = 0L
        )
    }

    private fun runTranscription(
        audioBytes: ByteArray,
        mimeType: String,
        durationSeconds: Long
    ) {
        val language = _transcribeDraft.value.selectedLanguage
        viewModelScope.launch {
            _transcribeDraft.update {
                it.copy(
                    isTranscribing = true,
                    errorMessage = null,
                    durationSeconds = durationSeconds
                )
            }
            try {
                val transcript = aiService.transcribeAudio(audioBytes, mimeType, language)
                val autoTitle = _transcribeDraft.value.title.ifBlank {
                    transcript.lineSequence().firstOrNull()?.take(42)?.trim().orEmpty()
                        .ifBlank { "تسجيل صوتي" }
                }
                _transcribeDraft.update {
                    it.copy(
                        title = autoTitle,
                        rawText = transcript,
                        organizedText = "",
                        savedNoteId = null,
                        isTranscribing = false
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Transcription timed out", e)
                _transcribeDraft.update {
                    it.copy(
                        isTranscribing = false,
                        errorMessage = "استغرقت عملية النسخ وقتاً طويلاً (أكثر من 60 ثانية). حاول بمقطع أقصر."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                _transcribeDraft.update {
                    it.copy(
                        isTranscribing = false,
                        errorMessage = e.localizedMessage ?: "فشل نسخ الصوت عبر النموذج."
                    )
                }
            }
        }
    }

    fun organizeDraftWithAi(style: TranscriptFormatStyle = _transcribeDraft.value.formatStyle) {
        val draft = _transcribeDraft.value
        if (draft.rawText.isBlank()) {
            _transcribeDraft.update { it.copy(errorMessage = "قم بتفريغ أو كتابة نص أولاً لتنظيمه.") }
            return
        }
        viewModelScope.launch {
            _transcribeDraft.update {
                it.copy(
                    formatStyle = style,
                    isOrganizing = true,
                    errorMessage = null,
                    organizedText = if (style == TranscriptFormatStyle.RAW) draft.rawText else ""
                )
            }
            try {
                aiService.organizeTranscriptStream(
                    rawText = draft.rawText,
                    formatStyle = style,
                    title = draft.title
                ).collect { partial ->
                    _transcribeDraft.update { it.copy(organizedText = partial) }
                }
                _transcribeDraft.update { it.copy(isOrganizing = false) }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Organizing timed out", e)
                _transcribeDraft.update {
                    it.copy(
                        isOrganizing = false,
                        errorMessage = "انتهت مهلة تنظيم النص، حاول مرة أخرى."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Organizing failed", e)
                _transcribeDraft.update {
                    it.copy(
                        isOrganizing = false,
                        errorMessage = e.localizedMessage ?: "تعذر تنظيم النص."
                    )
                }
            }
        }
    }

    fun saveDraftToCloud() {
        val draft = _transcribeDraft.value
        if (draft.rawText.isBlank()) {
            _transcribeDraft.update { it.copy(errorMessage = "لا يوجد نص لحفظه.") }
            return
        }
        viewModelScope.launch {
            _transcribeDraft.update { it.copy(isSaving = true, errorMessage = null) }
            val existingId = draft.savedNoteId
            if (existingId == null) {
                val res = repository.saveTranscript(
                    title = draft.title.ifBlank { "نسخة صوتية" },
                    rawText = draft.rawText,
                    organizedText = draft.organizedText.ifBlank { null },
                    language = draft.selectedLanguage.code,
                    formatStyle = draft.formatStyle,
                    durationSeconds = draft.durationSeconds
                )
                res.onSuccess { newId ->
                    _transcribeDraft.update { it.copy(isSaving = false, savedNoteId = newId) }
                    _statusMessages.trySend("تم حفظ النص في مكتبتك السحابية")
                }.onFailure { err ->
                    _transcribeDraft.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = err.localizedMessage ?: "تعذر حفظ النص."
                        )
                    }
                }
            } else {
                val res = repository.updateTranscript(
                    transcriptId = existingId,
                    title = draft.title.ifBlank { "نسخة صوتية" },
                    rawText = draft.rawText,
                    organizedText = draft.organizedText.ifBlank { null },
                    formatStyle = draft.formatStyle
                )
                res.onSuccess {
                    _transcribeDraft.update { it.copy(isSaving = false) }
                    _statusMessages.trySend("تم تحديث النص المحفوظ")
                }.onFailure { err ->
                    _transcribeDraft.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = err.localizedMessage ?: "تعذر تحديث النص."
                        )
                    }
                }
            }
        }
    }

    fun clearTranscribeDraft() {
        _transcribeDraft.value = TranscribeDraftState(
            selectedLanguage = _transcribeDraft.value.selectedLanguage
        )
    }

    fun buildDraftAsNote(): TranscriptNote {
        val d = _transcribeDraft.value
        return TranscriptNote(
            id = d.savedNoteId.orEmpty(),
            userId = currentUserId,
            title = d.title.ifBlank { "نسخة صوتية" },
            rawText = d.rawText,
            organizedText = d.organizedText.ifBlank { null },
            language = d.selectedLanguage.code,
            formatStyle = d.formatStyle.name,
            durationSeconds = d.durationSeconds
        )
    }

    fun updateSavedTranscript(
        note: TranscriptNote,
        newTitle: String,
        newRaw: String,
        newOrganized: String,
        newStyle: TranscriptFormatStyle
    ) {
        viewModelScope.launch {
            val res = repository.updateTranscript(
                transcriptId = note.id,
                title = newTitle,
                rawText = newRaw,
                organizedText = newOrganized.ifBlank { null },
                formatStyle = newStyle
            )
            res.onSuccess {
                _statusMessages.trySend("تم حفظ التعديلات")
            }.onFailure { err ->
                _statusMessages.trySend(err.localizedMessage ?: "تعذر تحديث الملاحظة")
            }
        }
    }

    fun deleteSavedTranscript(transcriptId: String) {
        viewModelScope.launch {
            val res = repository.deleteTranscript(transcriptId)
            res.onSuccess {
                if (_transcribeDraft.value.savedNoteId == transcriptId) {
                    _transcribeDraft.update { it.copy(savedNoteId = null) }
                }
                _statusMessages.trySend("تم حذف النص")
            }.onFailure { err ->
                _statusMessages.trySend(err.localizedMessage ?: "تعذر حذف النص")
            }
        }
    }

    fun organizeSavedTranscriptWithAi(
        note: TranscriptNote,
        style: TranscriptFormatStyle,
        onCompleted: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                var latest = ""
                aiService.organizeTranscriptStream(note.rawText, style, note.title).collect { chunk ->
                    latest = chunk
                }
                if (latest.isNotBlank()) {
                    repository.updateTranscript(
                        transcriptId = note.id,
                        title = note.title,
                        rawText = note.rawText,
                        organizedText = latest,
                        formatStyle = style
                    )
                    onCompleted(latest)
                    _statusMessages.trySend("تم تنظيم وحفظ النص")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusMessages.trySend(e.localizedMessage ?: "تعذر تنظيم النص")
            }
        }
    }

    // --- Voice Studio Actions ---

    fun updateTtsText(text: String) {
        _voiceStudio.update { it.copy(ttsText = text.take(5000)) }
    }

    fun updateProfileNameDraft(name: String) {
        _voiceStudio.update { it.copy(profileNameDraft = name.take(60)) }
    }

    fun updateBaseVoice(voiceName: String) {
        _voiceStudio.update { it.copy(baseVoiceName = voiceName) }
    }

    fun updatePitchShift(pitch: Double) {
        _voiceStudio.update { it.copy(pitchShift = pitch.coerceIn(0.75, 1.35)) }
    }

    fun updateSpeechRate(rate: Double) {
        _voiceStudio.update { it.copy(speechRate = rate.coerceIn(0.75, 1.35)) }
    }

    fun selectVoiceProfile(profile: VoiceProfile) {
        _voiceStudio.update {
            it.copy(
                selectedProfileId = profile.id,
                profileNameDraft = profile.displayName,
                baseVoiceName = profile.baseVoiceName,
                toneDescription = profile.toneDescription,
                deliveryStyle = profile.deliveryStyle,
                pitchShift = profile.pitchShift,
                speechRate = profile.speechRate
            )
        }
    }

    fun startVoiceSampleRecording() {
        if (recorder.isRecording.value && !_voiceStudio.value.isRecordingSample) {
            recorder.cancelRecording()
        }
        player.stop()
        _voiceStudio.update { it.copy(errorMessage = null, isRecordingSample = true) }
        val res = recorder.startRecording(viewModelScope, filePrefix = "voice_sample")
        if (res.isFailure) {
            _voiceStudio.update {
                it.copy(
                    isRecordingSample = false,
                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "تعذر بدء تسجيل عينة الصوت."
                )
            }
        }
    }

    fun stopAndAnalyzeVoiceSample() {
        val stopResult = recorder.stopRecording()
        _voiceStudio.update { it.copy(isRecordingSample = false) }
        val recorded = stopResult.getOrElse { err ->
            _voiceStudio.update {
                it.copy(errorMessage = err.localizedMessage ?: "تعذر حفظ عينة الصوت.")
            }
            return
        }

        viewModelScope.launch {
            _voiceStudio.update { it.copy(isAnalyzingSample = true, errorMessage = null) }
            try {
                val traits = aiService.analyzeVoiceSample(recorded.bytes, recorded.mimeType)
                val profileName = _voiceStudio.value.profileNameDraft.ifBlank { "نبرتي الشخصية" }
                val saveRes = repository.saveVoiceProfile(
                    displayName = profileName,
                    baseVoiceName = traits.baseVoiceName,
                    toneDescription = traits.toneDescription,
                    deliveryStyle = traits.deliveryStyle,
                    pitchShift = traits.recommendedPitch,
                    speechRate = traits.recommendedRate,
                    sampleDurationSeconds = recorded.durationSeconds
                )
                saveRes.onSuccess { newId ->
                    _voiceStudio.update {
                        it.copy(
                            isAnalyzingSample = false,
                            selectedProfileId = newId,
                            baseVoiceName = traits.baseVoiceName,
                            toneDescription = traits.toneDescription,
                            deliveryStyle = traits.deliveryStyle,
                            pitchShift = traits.recommendedPitch,
                            speechRate = traits.recommendedRate
                        )
                    }
                    _statusMessages.trySend("تم تحليل بصمة صوتك وحفظها")
                }.onFailure { err ->
                    _voiceStudio.update {
                        it.copy(
                            isAnalyzingSample = false,
                            baseVoiceName = traits.baseVoiceName,
                            toneDescription = traits.toneDescription,
                            deliveryStyle = traits.deliveryStyle,
                            pitchShift = traits.recommendedPitch,
                            speechRate = traits.recommendedRate,
                            errorMessage = err.localizedMessage ?: "تم التحليل لكن تعذر الحفظ في السحابة."
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Voice sample analysis timed out", e)
                _voiceStudio.update {
                    it.copy(
                        isAnalyzingSample = false,
                        errorMessage = "استغرق تحليل الصوت وقتاً طويلاً، حاول مرة أخرى."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Voice analysis failed", e)
                _voiceStudio.update {
                    it.copy(
                        isAnalyzingSample = false,
                        errorMessage = e.localizedMessage ?: "فشل تحليل عينة الصوت."
                    )
                }
            }
        }
    }

    fun saveVoiceTuningChanges() {
        val state = _voiceStudio.value
        val profileId = state.selectedProfileId
        viewModelScope.launch {
            if (profileId == null) {
                val res = repository.saveVoiceProfile(
                    displayName = state.profileNameDraft.ifBlank { "نبرتي المخصصة" },
                    baseVoiceName = state.baseVoiceName,
                    toneDescription = state.toneDescription.ifBlank { "إعداد صوتي مخصص يدوياً" },
                    deliveryStyle = state.deliveryStyle,
                    pitchShift = state.pitchShift,
                    speechRate = state.speechRate,
                    sampleDurationSeconds = 0L
                )
                res.onSuccess { newId ->
                    _voiceStudio.update { it.copy(selectedProfileId = newId) }
                    _statusMessages.trySend("تم حفظ إعدادات الصوت")
                }.onFailure { err ->
                    _statusMessages.trySend(err.localizedMessage ?: "تعذر حفظ الإعدادات")
                }
            } else {
                val res = repository.updateVoiceProfileTuning(
                    profileId = profileId,
                    baseVoiceName = state.baseVoiceName,
                    pitchShift = state.pitchShift,
                    speechRate = state.speechRate
                )
                res.onSuccess {
                    _statusMessages.trySend("تم تحديث ضبط الصوت")
                }.onFailure { err ->
                    _statusMessages.trySend(err.localizedMessage ?: "تعذر تحديث الصوت")
                }
            }
        }
    }

    fun deleteVoiceProfile(profileId: String) {
        viewModelScope.launch {
            repository.deleteVoiceProfile(profileId).onSuccess {
                if (_voiceStudio.value.selectedProfileId == profileId) {
                    _voiceStudio.update { it.copy(selectedProfileId = null, toneDescription = "") }
                }
                _statusMessages.trySend("تم حذف بصمة الصوت")
            }
        }
    }

    fun generateSpeechFromText(textOverride: String? = null) {
        val state = _voiceStudio.value
        val inputText = (textOverride ?: state.ttsText).trim()
        if (inputText.isEmpty()) {
            _voiceStudio.update { it.copy(errorMessage = "أدخل النص الذي تريد تحويله إلى كلام.") }
            return
        }
        val activeProfile = VoiceProfile(
            id = state.selectedProfileId.orEmpty(),
            userId = currentUserId,
            displayName = state.profileNameDraft.ifBlank { "نبرتي" },
            baseVoiceName = state.baseVoiceName,
            toneDescription = state.toneDescription,
            deliveryStyle = state.deliveryStyle,
            pitchShift = state.pitchShift,
            speechRate = state.speechRate
        )

        viewModelScope.launch {
            player.stop()
            _voiceStudio.update {
                it.copy(
                    ttsText = inputText,
                    isSynthesizing = true,
                    errorMessage = null
                )
            }
            try {
                val synthesized = aiService.synthesizeSpeech(inputText, activeProfile)
                _voiceStudio.update {
                    it.copy(
                        isSynthesizing = false,
                        lastSynthesized = synthesized
                    )
                }
                playAudio(synthesized, activeProfile.pitchShift.toFloat(), activeProfile.speechRate.toFloat())
                repository.saveGeneratedSpeech(
                    text = inputText,
                    voiceProfileName = activeProfile.displayName,
                    baseVoiceName = activeProfile.baseVoiceName,
                    pitchShift = activeProfile.pitchShift,
                    speechRate = activeProfile.speechRate
                )
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "TTS timed out", e)
                _voiceStudio.update {
                    it.copy(
                        isSynthesizing = false,
                        errorMessage = "انتهت مهلة توليد الصوت، حاول بنص أقصر."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "TTS failed", e)
                _voiceStudio.update {
                    it.copy(
                        isSynthesizing = false,
                        errorMessage = e.localizedMessage ?: "فشل توليد الصوت."
                    )
                }
            }
        }
    }

    fun replayLastSynthesized() {
        val state = _voiceStudio.value
        val audio = state.lastSynthesized ?: return
        if (player.isPlaying.value) {
            player.stop()
        } else {
            playAudio(audio, state.pitchShift.toFloat(), state.speechRate.toFloat())
        }
    }

    fun replayHistoryItem(item: GeneratedSpeech) {
        _voiceStudio.update {
            it.copy(
                ttsText = item.text,
                baseVoiceName = item.baseVoiceName,
                pitchShift = item.pitchShift,
                speechRate = item.speechRate
            )
        }
        generateSpeechFromText(item.text)
    }

    fun stopAudioPlayback() {
        player.stop()
    }

    fun sendTextToVoiceStudio(text: String) {
        _voiceStudio.update { it.copy(ttsText = text.take(5000)) }
    }

    private fun playAudio(audio: SynthesizedAudio, pitch: Float, speed: Float) {
        val res = player.play(
            audioBytes = audio.wavBytes,
            mimeType = audio.mimeType,
            pitch = pitch,
            speed = speed,
            scope = viewModelScope
        )
        if (res.isFailure) {
            _voiceStudio.update {
                it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "تعذر تشغيل المقطع الصوتي.")
            }
        }
    }

    fun cleanupAudioOnSignOut() {
        recorder.cancelRecording()
        player.stop()
    }

    override fun onCleared() {
        super.onCleared()
        recorder.cancelRecording()
        player.stop()
    }

    private companion object {
        const val TAG = "NabraViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
