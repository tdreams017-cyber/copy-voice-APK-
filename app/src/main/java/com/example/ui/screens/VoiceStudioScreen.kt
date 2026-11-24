package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.TtsVoicePreset
import com.example.data.AudioExportFormat
import com.example.data.DetectedEmotion
import com.example.data.UiState
import com.example.export.TranscriptFormatter
import com.example.ui.NabraViewModel
import com.example.ui.components.AudioWaveformVisualizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private enum class MicCaptureTarget {
    VOICE_SAMPLE,
    TTS_DICTATION
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceStudioScreen(
    viewModel: NabraViewModel,
    onStatusMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val studio by viewModel.voiceStudio.collectAsStateWithLifecycle()
    val profilesState by viewModel.voiceProfilesState.collectAsStateWithLifecycle()
    val speechesState by viewModel.generatedSpeechesState.collectAsStateWithLifecycle()
    val savedApiKey by viewModel.voiceCloneApiKey.collectAsStateWithLifecycle()

    val isRecordingMic by viewModel.isRecordingMic.collectAsStateWithLifecycle()
    val amplitude by viewModel.recordingAmplitude.collectAsStateWithLifecycle()
    val elapsedSeconds by viewModel.recordingElapsedSeconds.collectAsStateWithLifecycle()

    val isPlaying by viewModel.isPlayingAudio.collectAsStateWithLifecycle()
    val playbackProgress by viewModel.playbackProgress.collectAsStateWithLifecycle()

    var micDenied by remember { mutableStateOf(false) }
    var pendingMicTarget by remember { mutableStateOf(MicCaptureTarget.VOICE_SAMPLE) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyDraft by remember(savedApiKey, showApiKeyDialog) { mutableStateOf(savedApiKey) }
    var revealApiKey by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micDenied = false
            when (pendingMicTarget) {
                MicCaptureTarget.VOICE_SAMPLE -> viewModel.startVoiceSampleRecording()
                MicCaptureTarget.TTS_DICTATION -> viewModel.startTtsMicRecording()
            }
        } else {
            micDenied = true
        }
    }

    val requestMicFor: (MicCaptureTarget) -> Unit = { target ->
        pendingMicTarget = target
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            micDenied = false
            when (target) {
                MicCaptureTarget.VOICE_SAMPLE -> viewModel.startVoiceSampleRecording()
                MicCaptureTarget.TTS_DICTATION -> viewModel.startTtsMicRecording()
            }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val voiceSampleFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val readResult = withContext(Dispatchers.IO) {
                    runCatching {
                        val mime = context.contentResolver.getType(uri) ?: "audio/mp4"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("تعذر قراءة الملف الصوتي.")
                        bytes to mime
                    }
                }
                readResult.onSuccess { (bytes, mime) ->
                    viewModel.cloneVoiceFromAudioFile(bytes, mime)
                }.onFailure { err ->
                    onStatusMessage(err.localizedMessage ?: "تعذر فتح الملف الصوتي")
                }
            }
        }
    }

    val exportWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(AudioExportFormat.WAV.mimeType)
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val prepared = viewModel.prepareExportAudioBytes(AudioExportFormat.WAV)
                prepared.onSuccess { bytes ->
                    val writeRes = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(bytes)
                            } ?: error("تعذر كتابة ملف WAV")
                        }
                    }
                    writeRes.onSuccess {
                        onStatusMessage("تم حفظ الملف الصوتي بصيغة WAV بنجاح")
                    }.onFailure { err ->
                        onStatusMessage(err.localizedMessage ?: "تعذر حفظ الملف الصوتي")
                    }
                }.onFailure { err ->
                    onStatusMessage(err.localizedMessage ?: "فشل تجهيز ملف WAV")
                }
            }
        }
    }

    val exportMp3Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(AudioExportFormat.MP3.mimeType)
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val prepared = viewModel.prepareExportAudioBytes(AudioExportFormat.MP3)
                prepared.onSuccess { bytes ->
                    val writeRes = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(bytes)
                            } ?: error("تعذر كتابة ملف MP3")
                        }
                    }
                    writeRes.onSuccess {
                        onStatusMessage("تم حفظ الملف الصوتي بصيغة MP3 بنجاح")
                    }.onFailure { err ->
                        onStatusMessage(err.localizedMessage ?: "تعذر حفظ ملف MP3")
                    }
                }.onFailure { err ->
                    onStatusMessage(err.localizedMessage ?: "فشل ترميز ملف MP3")
                }
            }
        }
    }

    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("ربط مفتاح API لاستنساخ الصوت") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "أدخل مفتاح ElevenLabs API لاستنساخ نبرة الصوت واللهجة مباشرة عند رفع ملف صوتي أو تسجيل عيّنة. يُحفظ المفتاح بأمان على جهازك.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = apiKeyDraft,
                        onValueChange = { apiKeyDraft = it },
                        label = { Text("مفتاح API") },
                        singleLine = true,
                        visualTransformation = if (revealApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { revealApiKey = !revealApiKey }) {
                                Icon(
                                    imageVector = if (revealApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (revealApiKey) "إخفاء المفتاح" else "إظهار المفتاح"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("api_key_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveVoiceCloneApiKey(apiKeyDraft)
                        showApiKeyDialog = false
                    },
                    modifier = Modifier.testTag("save_api_key_button")
                ) {
                    Text("حفظ والربط")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (savedApiKey.isNotBlank()) {
                        TextButton(
                            onClick = {
                                viewModel.saveVoiceCloneApiKey("")
                                showApiKeyDialog = false
                            }
                        ) {
                            Text("فصل المفتاح", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { showApiKeyDialog = false }) {
                        Text("إلغاء")
                    }
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: Personal Voice Fingerprint & Cloning
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Column {
                                Text(
                                    text = "استنساخ الصوت وبصمة النبرة",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "سجّل عيّنة أو ارفع ملفاً صوتياً لاستنساخ النبرة واللهجة.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { showApiKeyDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("api_key_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = "مفتاح API",
                                tint = if (savedApiKey.isNotBlank()) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (savedApiKey.isNotBlank()) "API مربوط" else "ربط API",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "نص مقترح للقراءة: \"مرحباً، هذا صوتي في تطبيق نبرة، أستخدمه لتحويل نصوصي المكتوبة إلى حديث واضح وطبيعي.\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    OutlinedTextField(
                        value = studio.profileNameDraft,
                        onValueChange = { viewModel.updateProfileNameDraft(it) },
                        label = { Text("اسم الصوت المستنسخ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (studio.isRecordingSample && isRecordingMic) {
                        AudioWaveformVisualizer(
                            amplitude = amplitude,
                            isRecording = true
                        )
                        Text(
                            text = "مدة العينة: ${TranscriptFormatter.formatDuration(elapsedSeconds)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (studio.isRecordingSample) {
                                    viewModel.stopAndAnalyzeVoiceSample()
                                } else {
                                    requestMicFor(MicCaptureTarget.VOICE_SAMPLE)
                                }
                            },
                            enabled = !studio.isAnalyzingSample && !studio.isRecordingTtsMic,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (studio.isRecordingSample) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                },
                                contentColor = if (studio.isRecordingSample) {
                                    MaterialTheme.colorScheme.onError
                                } else {
                                    MaterialTheme.colorScheme.onSecondary
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("record_voice_sample_button")
                        ) {
                            if (studio.isAnalyzingSample) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("جارٍ الاستنساخ...")
                            } else {
                                Icon(
                                    imageVector = if (studio.isRecordingSample) Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (studio.isRecordingSample) "إيقاف وتحليل" else "تسجيل عيّنة")
                            }
                        }

                        if (!studio.isRecordingSample) {
                            OutlinedButton(
                                onClick = { voiceSampleFileLauncher.launch(arrayOf("audio/*")) },
                                enabled = !studio.isAnalyzingSample,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("upload_voice_sample_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.UploadFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("رفع ملف صوتي")
                            }
                        }
                    }

                    if (micDenied) {
                        Text(
                            text = "يرجى السماح بالوصول للميكروفون لتسجيل الصوت.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Saved profiles switcher
                    if (profilesState is UiState.Success) {
                        val profiles = (profilesState as UiState.Success).data
                        if (profiles.isNotEmpty()) {
                            Text(
                                text = "بصماتك وأصواتك المحفوظة:",
                                style = MaterialTheme.typography.labelLarge
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                profiles.forEach { profile ->
                                    FilterChip(
                                        selected = studio.selectedProfileId == profile.id,
                                        onClick = { viewModel.selectVoiceProfile(profile) },
                                        label = {
                                            Text(
                                                if (!profile.clonedVoiceId.isNullOrBlank()) {
                                                    "${profile.displayName} (مستنسخ)"
                                                } else {
                                                    profile.displayName
                                                }
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { viewModel.deleteVoiceProfile(profile.id) },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "حذف البصمة",
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (studio.clonedVoiceId != null && savedApiKey.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "مرتبط بمحرك الاستنساخ الفعلي عبر API لتوليد كلام مطابق لصوتك ولهجتك.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    if (studio.toneDescription.isNotBlank() || studio.dialectNotes.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (studio.toneDescription.isNotBlank()) {
                                    Text(
                                        text = "تحليل الصوت: ${studio.toneDescription}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (studio.dialectNotes.isNotBlank()) {
                                    Text(
                                        text = "اللهجة والأسلوب: ${studio.dialectNotes}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Voice Persona + Pitch/Rate/Depth Fine Tuning
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "تخصيص مخرجات الصوت:",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TtsVoicePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = studio.baseVoiceName == preset.voiceId,
                                onClick = { viewModel.updateBaseVoice(preset.voiceId) },
                                label = { Text(preset.labelAr) }
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("درجة الصوت (Pitch)", style = MaterialTheme.typography.labelMedium)
                            Text(
                                String.format(Locale.US, "%.2fx", studio.pitchShift),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = studio.pitchShift.toFloat(),
                            onValueChange = { viewModel.updatePitchShift(it.toDouble()) },
                            valueRange = 0.75f..1.35f,
                            modifier = Modifier.testTag("pitch_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("سرعة الكلام", style = MaterialTheme.typography.labelMedium)
                            Text(
                                String.format(Locale.US, "%.2fx", studio.speechRate),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = studio.speechRate.toFloat(),
                            onValueChange = { viewModel.updateSpeechRate(it.toDouble()) },
                            valueRange = 0.75f..1.35f,
                            modifier = Modifier.testTag("rate_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("عمق الصوت (الرخامة)", style = MaterialTheme.typography.labelMedium)
                            val depthPercent = (studio.voiceDepth * 100).roundToInt()
                            val depthLabel = when {
                                depthPercent > 5 -> "عميق +$depthPercent%"
                                depthPercent < -5 -> "خفيف $depthPercent%"
                                else -> "متوازن 0%"
                            }
                            Text(
                                depthLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = studio.voiceDepth.toFloat(),
                            onValueChange = { viewModel.updateVoiceDepth(it.toDouble()) },
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier.testTag("depth_slider")
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.saveVoiceTuningChanges() },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("حفظ إعدادات الصوت")
                    }
                }
            }

            if (studio.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = studio.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            // Card 2: Text-to-Speech Generator with Mic Dictation, Emotion Analysis & Export
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "توليد الكلام بصوتك",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        Button(
                            onClick = {
                                if (studio.isRecordingTtsMic) {
                                    viewModel.stopTtsMicAndSpeak()
                                } else {
                                    requestMicFor(MicCaptureTarget.TTS_DICTATION)
                                }
                            },
                            enabled = !studio.isTranscribingTtsMic && !studio.isSynthesizing && !studio.isRecordingSample,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (studio.isRecordingTtsMic) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                contentColor = if (studio.isRecordingTtsMic) {
                                    MaterialTheme.colorScheme.onError
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("tts_mic_dictate_button")
                        ) {
                            if (studio.isTranscribingTtsMic) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("جارٍ الفهم...")
                            } else {
                                Icon(
                                    imageVector = if (studio.isRecordingTtsMic) Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = "ميكروفون للتشغيل",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (studio.isRecordingTtsMic) "إيقاف وتشغيل" else "ميكروفون")
                            }
                        }
                    }

                    if (studio.isRecordingTtsMic && isRecordingMic) {
                        AudioWaveformVisualizer(
                            amplitude = amplitude,
                            isRecording = true
                        )
                        Text(
                            text = "تحدّث الآن لتحويل كلامك وتشغيله (${TranscriptFormatter.formatDuration(elapsedSeconds)})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "تشغيل صوتي تلقائي فور انتهاء الميكروفون",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = studio.autoSpeakAfterTtsMic,
                            onCheckedChange = { viewModel.setAutoSpeakAfterTtsMic(it) }
                        )
                    }

                    OutlinedTextField(
                        value = studio.ttsText,
                        onValueChange = { viewModel.updateTtsText(it) },
                        label = { Text("اكتب أو تحدّث بالكلام المراد إلقاؤه") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tts_text_input")
                    )

                    // Emotion Analysis & Tone Adaptation Card
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Mood,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "تحليل المشاعر وتكييف النبرة",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    TextButton(
                                        onClick = { viewModel.analyzeEmotionNow() },
                                        enabled = !studio.isAnalyzingEmotion && studio.ttsText.isNotBlank(),
                                        modifier = Modifier.testTag("analyze_emotion_button")
                                    ) {
                                        if (studio.isAnalyzingEmotion) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("اكتشاف")
                                        }
                                    }
                                    Switch(
                                        checked = studio.autoEmotionEnabled,
                                        onCheckedChange = { viewModel.setAutoEmotionEnabled(it) }
                                    )
                                }
                            }

                            Text(
                                text = "المشاعر السائدة: ${studio.detectedEmotion.emotion.labelAr} • ${studio.detectedEmotion.deliveryCueAr}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                DetectedEmotion.entries.forEach { emotion ->
                                    FilterChip(
                                        selected = studio.detectedEmotion.emotion == emotion,
                                        onClick = { viewModel.selectManualEmotion(emotion) },
                                        label = { Text(emotion.labelAr) },
                                        modifier = Modifier.testTag("emotion_chip_${emotion.name}")
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.generateSpeechFromText() },
                        enabled = !studio.isSynthesizing && !studio.isTranscribingTtsMic && studio.ttsText.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("generate_speech_button")
                    ) {
                        if (studio.isSynthesizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (studio.isAnalyzingEmotion) "جارٍ تحليل المشاعر..." else "جارٍ توليد الصوت..."
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "توليد الكلام الآن",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    if (studio.lastSynthesized != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Button(
                                        onClick = { viewModel.replayLastSynthesized() },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.testTag("play_tts_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                            contentDescription = if (isPlaying) "إيقاف" else "تشغيل"
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isPlaying) "إيقاف" else "تشغيل")
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        AudioExportFormat.entries.forEach { format ->
                                            FilterChip(
                                                selected = studio.selectedExportFormat == format,
                                                onClick = { viewModel.setExportFormat(format) },
                                                label = { Text(format.labelAr) },
                                                modifier = Modifier.testTag("export_format_${format.name}")
                                            )
                                        }
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        val stamp = System.currentTimeMillis()
                                        when (studio.selectedExportFormat) {
                                            AudioExportFormat.WAV ->
                                                exportWavLauncher.launch("nabra_voice_$stamp.wav")
                                            AudioExportFormat.MP3 ->
                                                exportMp3Launcher.launch("nabra_voice_$stamp.mp3")
                                        }
                                    },
                                    enabled = !studio.isExportingAudio,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("export_audio_button")
                                ) {
                                    if (studio.isExportingAudio) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("جارٍ تجهيز الملف...")
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.AudioFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("تصدير وحفظ بصيغة ${studio.selectedExportFormat.labelAr}")
                                    }
                                }

                                LinearProgressIndicator(
                                    progress = { playbackProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // Recent TTS Generations
            if (speechesState is UiState.Success) {
                val history = (speechesState as UiState.Success).data
                if (history.isNotEmpty()) {
                    Text(
                        text = "آخر المقاطع المولّدة",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.take(8).forEach { item ->
                            val emotionLabel = DetectedEmotion.fromRaw(item.detectedEmotion).labelAr
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.voiceProfileName} • $emotionLabel • ${item.baseVoiceName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { viewModel.replayHistoryItem(item) }) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "إعادة التوليد والتشغيل",
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
