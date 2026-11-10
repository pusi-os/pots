package com.pusios.pots.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaModel
import com.pusios.pots.ui.tts.PotsTextToSpeech
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.handsfree_content_description
import kotlin.math.roundToInt

/**
 * Media Studio surface: canvas (elements) above, prompt + model dock below.
 * Agent chat and voice stay in [onAskAgent] (wired by the host screen).
 */
@Composable
fun MediaStudioScreen(
    viewModel: MediaStudioViewModel,
    modifier: Modifier = Modifier,
    textToSpeech: PotsTextToSpeech? = null,
    onAskAgent: (String) -> Unit = {},
    onExit: () -> Unit = {},
) {
    val elements by viewModel.elements.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val models by viewModel.models.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val references by viewModel.references.collectAsState()
    val error by viewModel.error.collectAsState()

    var prompt by remember { mutableStateOf("") }
    var isVideoMode by remember { mutableStateOf(false) }
    var canvasScale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }

    val showAllElements = remember(elements) { elements.sortedBy { it.zIndex } }
    val pending = jobs.values.filter { !it.isTerminal }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onExit) { Text("Чат") }
            Spacer(Modifier.weight(1f))
            Text("Медиа", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.loadModels() }) { Text("Модели") }
        }

        // Canvas zone (in place of chat history).
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = "Медиа-канвас" }
                .transformable(
                    state = rememberTransformableState { zoomChange, panChange, _ ->
                        canvasScale = (canvasScale * zoomChange).coerceIn(0.5f, 3f)
                        canvasOffset += panChange
                    },
                )
                .clickable {
                    viewModel.select(null)
                    menuFor = null
                },
        ) {
            showAllElements.forEach { element ->
                Box(
                    Modifier
                        .offset { IntOffset((element.x + canvasOffset.x).roundToInt(), (element.y + canvasOffset.y).roundToInt()) }
                        .size(160.dp)
                        .graphicsLayer(
                            scaleX = element.scale * canvasScale,
                            scaleY = element.scale * canvasScale,
                            rotationZ = element.rotation,
                        )
                        .then(
                            if (element.id == selectedId || element.id == menuFor) {
                                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                            } else {
                                Modifier
                            },
                        )
                        .pointerInput(element.id) {
                            detectDragGestures(
                                onDragEnd = {},
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    viewModel.move(element.id, dragAmount.x, dragAmount.y)
                                },
                            )
                        }
                        .semantics { contentDescription = if (element.isVideo) "Видео-элемент" else "Изображение" }
                        .clickable(role = Role.Button) {
                            viewModel.select(element.id)
                            menuFor = element.id
                            menuOffset = Offset(element.x, element.y + 160f)
                        },
                ) {
                    if (element.isVideo) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), Alignment.Center) {
                            Text("▶", style = MaterialTheme.typography.displayMedium)
                            val url = element.url ?: ""
                            if (url.isNotBlank()) {
                                AsyncImage(model = url, contentDescription = "Видео", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                    } else {
                        AsyncImage(
                            model = element.url,
                            contentDescription = "Изображение на канвасе",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            pending.forEach { job ->
                Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            if (showAllElements.isEmpty() && pending.isEmpty()) {
                Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Сгенерируй первое изображение или видео", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Выбери модель ниже и нажми «Создать»", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Floating context menu anchored under the tap point.
            menuFor?.let { id ->
                Surface(
                    modifier = Modifier.offset { menuOffset.toIntOffset() }.padding(8.dp),
                    tonalElevation = 4.dp,
                ) {
                    Column(Modifier.padding(6.dp)) {
                        listOf(
                            "Вперёд" to CanvasAction.BringForward,
                            "Назад" to CanvasAction.SendBack,
                            "Дублировать" to CanvasAction.Duplicate,
                            "Как референс" to CanvasAction.UseAsReference,
                            "Удалить" to CanvasAction.Delete,
                            "Закрыть" to CanvasAction.ClearSelection,
                        ).forEach { (label, action) ->
                            TextButton(onClick = {
                                viewModel.applyAction(id, action)
                                if (action != CanvasAction.ClearSelection) menuFor = null else menuFor = null
                            }) { Text(label) }
                        }
                    }
                }
            }
        }

        if (references.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Референсы: ${references.size}", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.clearReferences() }) { Text("Сбросить") }
            }
        }

        error?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
            }
        }

        // Model picker (image/video).
        LazyColumn(Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 12.dp)) {
            items(models.filter { if (isVideoMode) it.kind == MediaKind.Video else it.kind == MediaKind.Image }) { model ->
                ModelRow(model = model, onSelect = {
                    if (model.kind == MediaKind.Video) viewModel.selectVideoModel(model.id) else viewModel.selectImageModel(model.id)
                })
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { isVideoMode = !isVideoMode }) {
                Text(if (isVideoMode) "Видео" else "Изображение")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                // Enhance via local template; Studio agent path replaces this in Phase 5.
                prompt = if (prompt.isNotBlank()) "$prompt, detailed, high quality, natural light" else prompt
            }) { Text("✨ Улучшить") }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                if (prompt.isBlank()) return@Button
                if (isVideoMode) viewModel.generateVideo(prompt) else viewModel.generateImage(prompt)
                prompt = ""
            }) { Text("Создать") }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Опиши изображение или видео…") },
            singleLine = false,
            minLines = 1,
            maxLines = 3,
        )

        // Agent composer: typed ask remains available beside generation.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Агент:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (prompt.isNotBlank()) onAskAgent(prompt) }) { Text("Спросить агента") }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.handsfree_content_description),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.semantics { contentDescription = "Голосовой ввод" },
            )
        }
    }
}

@Composable
private fun ModelRow(model: MediaModel, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(model.id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        val price = model.fromRub?.let { "от $it ₽" } ?: ""
        val unit = model.unit ?: ""
        Text(price + if (price.isNotEmpty() && unit.isNotEmpty()) " · $unit" else unit, style = MaterialTheme.typography.labelSmall)
    }
}

private fun Offset.toIntOffset(): IntOffset = IntOffset(x.roundToInt(), y.roundToInt())
