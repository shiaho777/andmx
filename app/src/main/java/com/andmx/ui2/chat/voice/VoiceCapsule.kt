package com.andmx.ui2.chat.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.andmx.ui2.settings.tr
import com.andmx.ui2.theme.LocalMotion

@Composable
fun VoiceMicButton(state: VoiceCapsuleState) {
    val engine = remember(state, state.downloadPhase) { state.engine() }
    if (engine == VoiceEngine.NONE || state.phase != VoicePhase.IDLE) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) state.begin() else state.permissionDenied()
    }
    var offerDownload by remember(state) { mutableStateOf(false) }
    var useCurrentEngine by remember(state) { mutableStateOf(false) }
    fun record() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) state.begin() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    if (offerDownload) {
        AlertDialog(
            onDismissRequest = { offerDownload = false },
            title = { Text(tr("语音输入")) },
            text = { Text(tr("启用离线转写需下载模型")) },
            confirmButton = {
                TextButton(onClick = {
                    offerDownload = false
                    state.startModelDownload()
                }) { Text(tr("下载离线模型（约75MB）")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    offerDownload = false
                    useCurrentEngine = true
                    record()
                }) { Text(tr("继续录音")) }
            },
        )
    }
    IconButton(
        onClick = {
            if (!useCurrentEngine && state.canOfferModelDownload()) offerDownload = true else record()
        },
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            Icons.Outlined.Mic,
            contentDescription = tr("语音输入"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun VoiceCapsuleBar(state: VoiceCapsuleState) {
    val motion = LocalMotion.current
    AnimatedVisibility(
        visible = state.phase != VoicePhase.IDLE,
        enter = motion.riseIn(),
        exit = motion.riseOut(),
    ) {
        when (state.phase) {
            VoicePhase.RECORDING -> RecordingCapsule(state)
            VoicePhase.TRANSCRIBING -> TranscribingCapsule(state)
            VoicePhase.IDLE -> Spacer(Modifier.size(0.dp))
        }
    }
    if (state.downloadPhase == WhisperDownloadPhase.DOWNLOADING) {
        ModelDownloadCapsule(state)
    }
    state.message?.let { msg ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                msg,
                style = MaterialTheme.typography.labelSmall,
                color = if (state.messageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(onClick = state::dismissMessage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, tr("关闭提示"), Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun RecordingCapsule(state: VoiceCapsuleState) {
    val motion = LocalMotion.current
    val transition = rememberInfiniteTransition(label = "voicePulse")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = motion.cursorPulse,
        label = "voicePulseAlpha",
    )
    val error = MaterialTheme.colorScheme.error
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(
                    width = 2.dp,
                    color = error.copy(alpha = (0.3f + 0.7f * pulse) * (0.6f + 0.4f * state.level)),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(error),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatVoiceDuration(state.elapsedMs),
            style = MaterialTheme.typography.labelMedium,
            color = error,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.weight(1f))
        FilledIconButton(
            onClick = state::stop,
            modifier = Modifier.size(28.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = error),
        ) {
            Icon(
                Icons.Outlined.Stop,
                contentDescription = tr("停止录音"),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ModelDownloadCapsule(state: VoiceCapsuleState) {
    LaunchedEffect(state) { state.downloadModel() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr("离线模型下载中") + " ${(state.downloadProgress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TranscribingCapsule(state: VoiceCapsuleState) {
    val motion = LocalMotion.current
    val transition = rememberInfiniteTransition(label = "voiceShimmer")
    val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = motion.shimmerSweep,
        label = "voiceShimmerSweep",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    val brush = Brush.linearGradient(
        0f to base,
        fraction.coerceIn(0.01f, 0.99f) to highlight,
        1f to base,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr("转写中…"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = state::cancel) {
            Text(tr("取消"), style = MaterialTheme.typography.labelMedium)
        }
    }
}
