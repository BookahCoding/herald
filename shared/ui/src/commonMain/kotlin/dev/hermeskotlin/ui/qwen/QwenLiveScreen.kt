package dev.hermeskotlin.ui.qwen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.qwen.QwenLine
import dev.hermeskotlin.core.qwen.QwenLiveState
import dev.hermeskotlin.core.qwen.QwenPhase
import dev.hermeskotlin.core.qwen.QwenSpeaker
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.sessions.SubpageHeader
import dev.hermeskotlin.ui.voice.rememberMicrophonePermission
import org.koin.compose.viewmodel.koinViewModel

/** Speech-to-speech with Qwen. The mic and the speaker stay on the phone; the API key stays on the proxy. */
@Composable
fun QwenLiveScreen(onBack: () -> Unit, viewModel: QwenLiveViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val microphone = rememberMicrophonePermission()
    PlatformBackHandler(enabled = true) {
        viewModel.stop()
        onBack()
    }
    QwenLiveContent(
        state = state,
        onBack = {
            viewModel.stop()
            onBack()
        },
        onStart = {
            microphone.withMicrophone(
                onDenied = { viewModel.showError("The microphone is off for Hikyaku. Allow it to start a live chat.") },
                onGranted = { viewModel.start() },
            )
        },
        onStop = viewModel::stop,
        onInterrupt = viewModel::interrupt,
        onDismissError = viewModel::dismissError,
    )
}

@Composable
internal fun QwenLiveContent(
    state: QwenLiveState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInterrupt: () -> Unit,
    onDismissError: () -> Unit,
) {
    val live = state.phase == QwenPhase.Connecting || state.phase == QwenPhase.Listening || state.phase == QwenPhase.Speaking
    val list = rememberLazyListState()
    LaunchedEffect(state.lines.size, state.lines.lastOrNull()?.text) {
        if (state.lines.isNotEmpty()) list.animateScrollToItem(state.lines.lastIndex)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader(
                title = "Live chat",
                subtitle = state.model ?: "Qwen-Audio realtime",
                onBack = onBack,
            )
            state.error?.let { message ->
                ErrorBanner(message, onDismissError, Modifier.padding(horizontal = 16.dp))
            }
            if (state.lines.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Tap Start and talk. Replies play as they stream, with the words here. The DashScope key stays on the proxy.",
                        style = Theme[typography][body],
                        color = Theme[colors][textSecondary],
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.lines, key = { it.id }) { line -> TranscriptLine(line) }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LevelRow(state.level, phaseLabel(state.phase))
                if (live) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button("Stop", onStop, Modifier.weight(1f), variant = ButtonVariant.Danger)
                        Button(
                            "Interrupt",
                            onInterrupt,
                            Modifier.weight(1f),
                            variant = ButtonVariant.Outline,
                            enabled = state.phase == QwenPhase.Speaking,
                        )
                    }
                } else {
                    Button("Start", onStart, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TranscriptLine(line: QwenLine) {
    val you = line.speaker == QwenSpeaker.You
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (you) Alignment.End else Alignment.Start) {
        Text(
            if (you) "You" else "Qwen",
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
        )
        Text(
            line.text,
            style = Theme[typography][body].copy(fontWeight = if (line.live) FontWeight.Medium else FontWeight.Normal),
            color = if (line.live) Theme[colors][accent] else Theme[colors][text],
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Theme[colors][if (you) surface3 else surface2])
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LevelRow(level: Float, label: String) {
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "$label. Microphone level." },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            repeat(8) { index ->
                val on = level > (index + 1) / 8f
                Box(
                    Modifier
                        .width(5.dp)
                        .height((8 + index * 2).dp)
                        .clip(CircleShape)
                        .background(Theme[colors][if (on) accent else surface3]),
                )
            }
        }
        Text(label, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Theme[colors][dangerSoft])
            .padding(14.dp)
            .semantics { contentDescription = message },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = Theme[typography][bodySmall].copy(fontSize = 14.sp), color = Theme[colors][danger])
        Button("Dismiss", onDismiss, variant = ButtonVariant.Ghost, size = dev.hermeskotlin.designsystem.components.ButtonSize.Small)
    }
}

private fun phaseLabel(phase: QwenPhase) = when (phase) {
    QwenPhase.Idle -> "Ready"
    QwenPhase.Connecting -> "Connecting"
    QwenPhase.Listening -> "Listening"
    QwenPhase.Speaking -> "Speaking"
    QwenPhase.Stopped -> "Stopped"
}
