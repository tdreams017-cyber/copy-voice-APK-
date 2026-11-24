package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AudioLanguageOption
import com.example.data.TranscriptFormatStyle
import com.example.export.TranscriptFormatter
import com.example.ui.NabraViewModel
import com.example.ui.components.AudioWaveformVisualizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscribeScreen(
    viewModel: NabraViewModel,
    onSendToVoiceStudio: (String) -> Unit,
    onExportSuccessMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by viewModel.transcribeDraft.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecordingMic.collectAsStateWithLifecycle()
    val amplitude by viewModel.recordingAmplitude.collectAsStateWithLifecycle()
    val elapsedSeconds by viewModel.recordingElapsedSeconds.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceStudio.collectAsStateWithLifecycle()

    val isTranscribeRecording = isRecording && !voiceState.isRecordingSample
    var permissionDeniedNotice by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDeniedNotice = false
            viewModel.startTranscribeRecording()
        } else {
            permissionDeniedNotice = true
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
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
                    viewModel.transcribeImportedAudio(bytes, mime)
                }.onFailure { err ->
                    onExportSuccessMessage(err.localizedMessage ?: "تعذر فتح الملف الصوتي")
                }
            }
        }
    }

    val exportTxtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val content = TranscriptFormatter.formatAsOrganizedTextFile(viewModel.buildDraftAsNote())
                val writeResult = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                            out.write(content.toByteArray(Charsets.UTF_8))
                        } ?: error("تعذر إنشاء الملف النصي.")
                    }
                }
                writeResult.onSuccess {
                    onExportSuccessMessage("تم تصدير الملف النصي المنظم بنجاح")
                }.onFailure { err ->
                    onExportSuccessMessage(err.localizedMessage ?: "فشل تصدير الملف")
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
            // Language selector row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioLanguageOption.entries.forEach { option ->
                    val selected = draft.selectedLanguage == option
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setLanguage(option) },
                        label = {
                            Text(
                                text = option.labelAr,
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("lang_chip_${option.code}")
                    )
                }
            }

            // Hero Recorder Card
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isTranscribeRecording) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
                                    MaterialTheme.colorScheme.surfaceContainer
                                )
                            )
                        )
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                isTranscribeRecording -> "جارٍ الاستماع للتسجيل..."
                                draft.isTranscribing -> "جارٍ التفريغ الصوتي..."
                                else -> "اضغط للتسجيل الصوتي"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isTranscribeRecording) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Surface(
                            color = if (isTranscribeRecording) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = TranscriptFormatter.formatDuration(
                                    if (isTranscribeRecording) elapsedSeconds else draft.durationSeconds
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isTranscribeRecording) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }

                    AudioWaveformVisualizer(
                        amplitude = amplitude,
                        isRecording = isTranscribeRecording
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isTranscribeRecording) {
                                    viewModel.stopAndTranscribeRecording()
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        viewModel.startTranscribeRecording()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            enabled = !draft.isTranscribing,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isTranscribeRecording) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                contentColor = if (isTranscribeRecording) {
                                    MaterialTheme.colorScheme.onError
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                }
                            ),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .size(76.dp)
                                .testTag("record_transcribe_button")
                        ) {
                            if (draft.isTranscribing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (isTranscribeRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = if (isTranscribeRecording) "إيقاف التسجيل" else "بدء التسجيل الصوتي",
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }

                        if (!isTranscribeRecording && !draft.isTranscribing) {
                            OutlinedButton(
                                onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("import_audio_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AudioFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("ملف صوتي")
                            }
                        }
                    }

                    if (permissionDeniedNotice) {
                        Text(
                            text = "يلزم منح إذن الميكروفون لتسجيل الصوت وتحويله إلى نص.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (draft.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = draft.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            // Transcript Editor & AI Organization Card
            AnimatedVisibility(visible = draft.rawText.isNotEmpty() || draft.isTranscribing) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "النص المفرّغ",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (draft.savedNoteId != null) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "محفوظ",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "محفوظ",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.clearTranscribeDraft() },
                                    modifier = Modifier.testTag("clear_draft_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "مسح المسودة"
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = draft.title,
                            onValueChange = { viewModel.updateDraftTitle(it) },
                            label = { Text("عنوان الملف") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("transcript_title_input")
                        )

                        OutlinedTextField(
                            value = draft.rawText,
                            onValueChange = { viewModel.updateDraftRawText(it) },
                            label = { Text("النص المنسوخ") },
                            minLines = 4,
                            maxLines = 10,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("transcript_raw_input")
                        )

                        // Organization format presets
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "تنظيم النص كملف مرتب:",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TranscriptFormatStyle.entries.forEach { style ->
                                    val selected = draft.formatStyle == style
                                    FilterChip(
                                        selected = selected,
                                        enabled = !draft.isOrganizing && draft.rawText.isNotBlank(),
                                        onClick = { viewModel.organizeDraftWithAi(style) },
                                        label = { Text(style.labelAr) },
                                        modifier = Modifier.testTag("format_chip_${style.name}")
                                    )
                                }
                            }
                        }

                        if (draft.isOrganizing || draft.organizedText.isNotBlank()) {
                            OutlinedTextField(
                                value = draft.organizedText,
                                onValueChange = { viewModel.updateDraftOrganizedText(it) },
                                label = {
                                    Text(
                                        if (draft.isOrganizing) "جارٍ تنسيق النص..." else "النص المنظم الجاهز للتصدير"
                                    )
                                },
                                minLines = 5,
                                maxLines = 12,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("transcript_organized_input")
                            )
                        }

                        // Primary actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { viewModel.saveDraftToCloud() },
                                enabled = !draft.isSaving && draft.rawText.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("save_transcript_button")
                            ) {
                                if (draft.isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.BookmarkAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (draft.savedNoteId == null) "حفظ النص" else "تحديث")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val fileName = TranscriptFormatter.suggestedFileName(draft.title)
                                    exportTxtLauncher.launch(fileName)
                                },
                                enabled = draft.rawText.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("export_txt_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("حفظ ملف TXT")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                onClick = {
                                    val formatted = TranscriptFormatter.formatAsOrganizedTextFile(
                                        viewModel.buildDraftAsNote()
                                    )
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, draft.title.ifBlank { "نص مفرّغ" })
                                        putExtra(Intent.EXTRA_TEXT, formatted)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "مشاركة النص"))
                                },
                                enabled = draft.rawText.isNotBlank(),
                                modifier = Modifier.testTag("share_draft_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("مشاركة")
                            }

                            TextButton(
                                onClick = {
                                    val body = draft.organizedText.ifBlank { draft.rawText }
                                    onSendToVoiceStudio(body)
                                },
                                enabled = draft.rawText.isNotBlank(),
                                modifier = Modifier.testTag("send_draft_to_tts_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "توليد بصوتي",
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
