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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.TtsVoicePreset
import com.example.data.UiState
import com.example.export.TranscriptFormatter
import com.example.ui.NabraViewModel
import com.example.ui.components.AudioWaveformVisualizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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

    val isRecordingMic by viewModel.isRecordingMic.collectAsStateWithLifecycle()
    val amplitude by viewModel.recordingAmplitude.collectAsStateWithLifecycle()
    val elapsedSeconds by viewModel.recordingElapsedSeconds.collectAsStateWithLifecycle()

    val isPlaying by viewModel.isPlayingAudio.collectAsStateWithLifecycle()
    val playbackProgress by viewModel.playbackProgress.collectAsStateWithLifecycle()

    var micDenied by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micDenied = false
            viewModel.startVoiceSampleRecording()
        } else {
            micDenied = true
        }
    }

    val exportWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        val wav = studio.lastSynthesized?.wavBytes
        if (uri != null && wav != null) {
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(wav)
                        } ?: error("تعذر حفظ الملف الصوتي")
                    }
                }
                res.onSuccess {
                    onStatusMessage("تم حفظ الملف الصوتي بنجاح")
                }.onFailure { err ->
                    onStatusMessage(err.localizedMessage ?: "تعذر حفظ الملف الصوتي")
                }
            }
        }
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
            // Card 1: Personal Voice Fingerprint Capture
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Column {
                            Text(
                                text = "بصمة صوتك الشخصية",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "سجّل 5–15 ثانية من صوتك لتحليل نبرتك ومطابقتها مع طبقة الصوت وسرعته.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        label = { Text("اسم البصمة الصوتية") },
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

                    Button(
                        onClick = {
                            if (studio.isRecordingSample) {
                                viewModel.stopAndAnalyzeVoiceSample()
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    viewModel.startVoiceSampleRecording()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        enabled = !studio.isAnalyzingSample,
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
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("record_voice_sample_button")
                    ) {
                        if (studio.isAnalyzingSample) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onSecondary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جارٍ تحليل نبرة صوتك...")
                        } else {
                            Icon(
                                imageVector = if (studio.isRecordingSample) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (studio.isRecordingSample) {
                                    "إيقاف وتحليل بصمة صوتي"
                                } else {
                                    "تسجيل عيّنة من صوتي"
                                }
                            )
                        }
                    }

                    if (micDenied) {
                        Text(
                            text = "يرجى السماح بالوصول للميكروفون لتسجيل عينة صوتك.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Saved profiles switcher
                    if (profilesState is UiState.Success) {
                        val profiles = (profilesState as UiState.Success).data
                        if (profiles.isNotEmpty()) {
                            Text(
                                text = "بصماتك المحفوظة:",
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
                                        label = { Text(profile.displayName) },
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

                    if (studio.toneDescription.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "تحليل الصوت: ${studio.toneDescription}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // Voice Persona + Pitch/Speed Fine Tuning
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
                            text = "ضبط النبرة والطابع الصوتي:",
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

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("طبقة الصوت (Pitch)", style = MaterialTheme.typography.labelMedium)
                            Text(
                                String.format(Locale.US, "%.2fx", studio.pitchShift),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = studio.pitchShift.toFloat(),
                            onValueChange = { viewModel.updatePitchShift(it.toDouble()) },
                            valueRange = 0.75f..1.35f
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
                            valueRange = 0.75f..1.35f
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.saveVoiceTuningChanges() },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("حفظ ضبط النبرة")
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

            // Card 2: Text-to-Speech Generator
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "تحويل النص إلى كلام بصوتك",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    OutlinedTextField(
                        value = studio.ttsText,
                        onValueChange = { viewModel.updateTtsText(it) },
                        label = { Text("اكتب أو الصق أي كلام تريد توليده") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tts_text_input")
                    )

                    Button(
                        onClick = { viewModel.generateSpeechFromText() },
                        enabled = !studio.isSynthesizing && studio.ttsText.isNotBlank(),
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
                            Text("جارٍ توليد الكلام...")
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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

                                        OutlinedButton(
                                            onClick = {
                                                exportWavLauncher.launch("nabra_voice_${System.currentTimeMillis()}.wav")
                                            },
                                            modifier = Modifier.testTag("export_wav_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.AudioFile,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("حفظ WAV")
                                        }
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
                                            text = "${item.voiceProfileName} • ${item.baseVoiceName}",
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
