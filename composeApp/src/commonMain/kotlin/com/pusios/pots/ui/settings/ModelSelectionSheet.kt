package com.pusios.pots.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.RecommendedModels
import com.pusios.pots.data.Service
import com.pusios.pots.data.priceTileCaption
import com.pusios.pots.formatContextWindow
import com.pusios.pots.formatReleaseDate
import com.pusios.pots.ui.PotsOutlinedTextField
import com.pusios.pots.ui.components.PotsSearchField
import com.pusios.pots.ui.components.VerticalScrollbarForGrid
import com.pusios.pots.ui.handCursor
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.ic_arrow_drop_down
import pots.composeapp.generated.resources.model_filter_free
import pots.composeapp.generated.resources.model_free_badge
import pots.composeapp.generated.resources.model_free_empty
import pots.composeapp.generated.resources.model_sort_ascending
import pots.composeapp.generated.resources.model_sort_context
import pots.composeapp.generated.resources.model_sort_date
import pots.composeapp.generated.resources.model_sort_descending
import pots.composeapp.generated.resources.model_sort_price
import pots.composeapp.generated.resources.model_sort_recommended
import pots.composeapp.generated.resources.settings_model_label
import pots.composeapp.generated.resources.settings_model_search
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelection(
    currentSelectedModel: SettingsModel?,
    models: ImmutableList<SettingsModel>,
    onClick: (String) -> Unit,
    showExtendedSorts: Boolean = false,
    /** Only the services with a curated entry get the class-based Recommended list. */
    service: Service? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // Provider list only — synthetic manual entries stay out of the picker grid.
    val pickerModels = remember(models) { models.filter { !it.isManualEntry } }
    if (pickerModels.isNotEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            PotsOutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = currentSelectedModel?.let { it.displayName ?: it.id } ?: "",
                onValueChange = {},
                readOnly = true,
                label = {
                    Text(
                        stringResource(Res.string.settings_model_label),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                trailingIcon = {
                    Icon(
                        modifier = Modifier.handCursor(),
                        imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
            )
            // Transparent overlay to capture clicks reliably on all platforms
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .handCursor()
                    .clickable { expanded = true },
            )
        }
        if (expanded) {
            ModalBottomSheet(
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                onDismissRequest = {
                    expanded = false
                },
            ) {
                var searchQuery by remember { mutableStateOf("") }
                val hasFreeModels = remember(pickerModels) { pickerModels.any { it.isFreeTier } }
                var freeFilterOnly by remember { mutableStateOf(false) }
                // Reset free filter when the service has no free models.
                LaunchedEffect(hasFreeModels) {
                    if (!hasFreeModels) freeFilterOnly = false
                }
                val filteredModels = remember(pickerModels, searchQuery, freeFilterOnly) {
                    pickerModels.filter { model ->
                        val matchesFree = !freeFilterOnly || model.isFreeTier
                        val matchesSearch = searchQuery.isBlank() ||
                            model.id.contains(searchQuery, ignoreCase = true) ||
                            model.subtitle.contains(searchQuery, ignoreCase = true) ||
                            model.displayName?.contains(searchQuery, ignoreCase = true) == true
                        matchesFree && matchesSearch
                    }
                }
                if (pickerModels.size > 6) {
                    PotsSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(Res.string.settings_model_search),
                    )
                }
                var sortOption by remember { mutableStateOf(initialModelSortOption()) }
                var sortAscending by remember { mutableStateOf(false) }
                val visibleModels = remember(filteredModels, sortOption, currentSelectedModel, service) {
                    when (sortOption) {
                        ModelSortOption.Price -> filteredModels.filter { it.id == currentSelectedModel?.id || withinMonths(it.releaseDate, 6) }

                        ModelSortOption.Recommended ->
                            RecommendedModels.selectRecommended(filteredModels, service, currentSelectedModel?.id)

                        else -> filteredModels
                    }
                }
                val sortedModels = remember(visibleModels, sortOption, sortAscending, searchQuery) {
                    val comparator = if (sortAscending && sortOption.supportsDirection) {
                        sortOption.comparator.reversed()
                    } else {
                        sortOption.comparator
                    }
                    val base = visibleModels.sortedWith(comparator)
                    if (searchQuery.isBlank()) {
                        base
                    } else {
                        base.sortedBy { model ->
                            val name = model.displayName ?: model.id
                            when {
                                name.contains(searchQuery, ignoreCase = true) -> 0
                                model.subtitle.contains(searchQuery, ignoreCase = true) -> 1
                                else -> 2
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModelSortOption.entries.forEach { option ->
                        if (!showExtendedSorts && (option == ModelSortOption.Price || option == ModelSortOption.Recommended)) return@forEach
                        val active = sortOption == option
                        val direction = when {
                            !active || !option.supportsDirection -> ""
                            sortAscending -> " · " + stringResource(Res.string.model_sort_ascending)
                            else -> " · " + stringResource(Res.string.model_sort_descending)
                        }
                        SortButton(
                            icon = option.icon,
                            label = stringResource(option.labelRes) + direction,
                            active = active,
                            ascending = if (active && option.supportsDirection) sortAscending else null,
                            modifier = Modifier.weight(1f).widthIn(max = 56.dp),
                            onClick = {
                                if (option == sortOption && option.supportsDirection) {
                                    sortAscending = !sortAscending
                                } else {
                                    sortOption = option
                                    sortAscending = option.ascendingByDefault
                                }
                            },
                        )
                    }
                    if (hasFreeModels) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)),
                        )
                        SortButton(
                            icon = Icons.Outlined.LocalOffer,
                            label = stringResource(Res.string.model_filter_free),
                            active = freeFilterOnly,
                            ascending = null,
                            filter = true,
                            modifier = Modifier.weight(1f).widthIn(max = 56.dp),
                            onClick = { freeFilterOnly = !freeFilterOnly },
                        )
                    }
                }
                val gridState = rememberLazyGridState()
                LaunchedEffect(sortOption, sortAscending, freeFilterOnly) {
                    gridState.requestScrollToItem(0)
                }
                if (sortedModels.isEmpty() && freeFilterOnly) {
                    Text(
                        text = stringResource(Res.string.model_free_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    Box {
                        LazyVerticalGrid(
                            GridCells.Adaptive(300.dp),
                            state = gridState,
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(sortedModels, key = { it.id }) { model ->
                                val select: () -> Unit = {
                                    onClick(model.id)
                                    expanded = false
                                }
                                val pick = if (sortOption == ModelSortOption.Recommended) {
                                    RecommendedModels.pickFor(service, model.id)
                                } else {
                                    null
                                }
                                if (pick != null) {
                                    RecommendedModelCard(
                                        model = model,
                                        pick = pick,
                                        isSelected = currentSelectedModel?.id == model.id,
                                        onClick = select,
                                    )
                                } else {
                                    ModelCard(
                                        model = model,
                                        priceUnit = priceTileCaption(service),
                                        isSelected = currentSelectedModel?.id == model.id,
                                        onClick = select,
                                    )
                                }
                            }
                        }
                        VerticalScrollbarForGrid(
                            gridState = gridState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

internal enum class ModelSortOption(
    val labelRes: StringResource,
    val icon: ImageVector,
    val comparator: Comparator<SettingsModel>,
    /** Direction the comparator already sorts in; the first tap lands here. */
    val ascendingByDefault: Boolean = false,
    /** False for composite keys, where the reverse order carries no meaning. */
    val supportsDirection: Boolean = true,
) {
    Date(Res.string.model_sort_date, Icons.Outlined.CalendarMonth, compareByDescending<SettingsModel> { it.releaseDate }.thenBy { it.id }),
    Ctx(Res.string.model_sort_context, Icons.Outlined.CropFree, compareByDescending<SettingsModel> { it.contextWindow }.thenBy { it.id }),
    Price(Res.string.model_sort_price, Icons.Filled.AttachMoney, compareBy<SettingsModel> { it.pricePerMillion ?: Double.MAX_VALUE }.thenBy { it.id }, ascendingByDefault = true),
    Recommended(Res.string.model_sort_recommended, Icons.Outlined.ThumbUp, compareByDescending<SettingsModel> { it.releaseDate }.thenBy { it.id }, supportsDirection = false),
}

/** The model sheet always opens on the Recommended tab, for every service. */
internal fun initialModelSortOption(): ModelSortOption = ModelSortOption.Recommended

private fun withinMonths(releaseDate: String?, months: Int): Boolean {
    if (releaseDate.isNullOrBlank()) return true
    val date = runCatching {
        LocalDate.parse(if (releaseDate.length == 7) "$releaseDate-01" else releaseDate)
    }.getOrNull() ?: return true
    val cutoffMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - months.toLong() * 30L * 24L * 60L * 60L * 1000L
    val cutoff = kotlinx.datetime.Instant.fromEpochMilliseconds(cutoffMs).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
    return date.toString() >= cutoff
}

@Composable
private fun ModelCard(model: SettingsModel, priceUnit: String?, isSelected: Boolean, onClick: () -> Unit) {
    val displayName = model.displayName?.takeIf { it.isNotBlank() && it != model.id }
    val title = displayName ?: model.id
    val secondary = if (displayName == null && model.subtitle.isNotBlank()) model.subtitle else null
    val releaseText = model.releaseDate?.let { formatReleaseDate(it) }
    // Context moved into the tile row, so the meta line keeps only what has no tile of its own.
    val metaText = listOfNotNull(releaseText, model.parameterCount)
        .joinToString("  ·  ").ifEmpty { if (displayName != null) model.id else null }

    val primaryColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.handCursor().clip(CardDefaults.shape).clickable { onClick() },
        shape = CardDefaults.shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = primaryColor,
                    modifier = Modifier.weight(1f),
                )
                if (model.isFreeTier) {
                    Spacer(Modifier.width(8.dp))
                    FreeTierBadge(isSelected = isSelected)
                }
                if (isSelected) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            secondary?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor,
                )
            }
            metaText?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModelTile(
                    icon = Icons.Filled.ArrowDownward,
                    value = model.pricePerMillion?.let(::formatPricePerMillion),
                    unit = priceUnit,
                    isSelected = isSelected,
                    modifier = Modifier.weight(1f),
                )
                ModelTile(
                    icon = Icons.Filled.ArrowUpward,
                    value = model.outputPricePerMillion?.let(::formatPricePerMillion),
                    unit = priceUnit,
                    isSelected = isSelected,
                    modifier = Modifier.weight(1f),
                )
                ModelTile(
                    icon = Icons.Outlined.CropFree,
                    value = model.contextWindow?.let { formatContextWindow(it) },
                    isSelected = isSelected,
                    modifier = Modifier.weight(1f),
                )
                ModelTile(
                    icon = Icons.Filled.Build,
                    value = if (model.supportsTools) "✓" else null,
                    isSelected = isSelected,
                    modifier = Modifier.weight(1f),
                )
                ModelTile(
                    icon = Icons.Filled.Visibility,
                    value = if (model.supportsVision) "✓" else null,
                    isSelected = isSelected,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Recommended-mode card: class chip, model name and the curated multi-line description. Distinct
 * from the plain [ModelCard] list — no tile row, an accent border marks the model's class.
 */
@Composable
private fun RecommendedModelCard(
    model: SettingsModel,
    pick: RecommendedModels.Pick,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val accent = when (pick.modelClass) {
        RecommendedModels.ModelClass.EVERYDAY -> Color(0xFF2E7D32)
        RecommendedModels.ModelClass.MIDDLE -> Color(0xFF1565C0)
        RecommendedModels.ModelClass.HARD -> Color(0xFF6A1B9A)
    }
    val classLabel = when (pick.modelClass) {
        RecommendedModels.ModelClass.EVERYDAY -> "Повседневная"
        RecommendedModels.ModelClass.MIDDLE -> "Средняя"
        RecommendedModels.ModelClass.HARD -> "Сложные задачи"
    }
    Card(
        modifier = Modifier.handCursor().clip(CardDefaults.shape).clickable { onClick() },
        shape = CardDefaults.shape,
        border = BorderStroke(
            width = 1.dp,
            color = accent.copy(alpha = if (isSelected) 0.75f else 0.35f),
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = classLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = model.displayName?.takeIf { it.isNotBlank() && it != model.id } ?: model.id,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                if (isSelected) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = pick.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * One value of a model card's tile row. A missing value keeps the tile's slot: the row must not
 * change height or shape when the list scrolls between models with different data.
 */
@Composable
private fun ModelTile(
    icon: ImageVector,
    value: String?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    isSelected: Boolean = false,
) {
    val hasData = value != null
    val colors = MaterialTheme.colorScheme
    val container = when {
        !hasData -> Color.Transparent
        isSelected -> colors.onPrimaryContainer.copy(alpha = 0.09f)
        else -> colors.onSurface.copy(alpha = 0.07f)
    }
    val borderColor = when {
        !hasData && isSelected -> colors.onPrimaryContainer.copy(alpha = 0.22f)
        !hasData -> colors.onSurface.copy(alpha = 0.16f)
        isSelected -> colors.onPrimaryContainer.copy(alpha = 0.16f)
        else -> colors.onSurface.copy(alpha = 0.09f)
    }
    val iconTint = when {
        !hasData && isSelected -> colors.onPrimaryContainer.copy(alpha = 0.35f)
        !hasData -> colors.onSurfaceVariant.copy(alpha = 0.45f)
        isSelected -> colors.onPrimaryContainer.copy(alpha = 0.62f)
        else -> colors.onSurfaceVariant
    }
    val contentColor = when {
        !hasData && isSelected -> colors.onPrimaryContainer.copy(alpha = 0.42f)
        !hasData -> colors.onSurfaceVariant.copy(alpha = 0.55f)
        isSelected -> colors.onPrimaryContainer
        else -> colors.onSurface
    }
    Box(
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
                )
                if (hasData && unit != null) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Icon-only sort control. The label lives in [contentDescription] and in a tooltip that Material
 * shows on long press, so the row stays one line wide without hiding what the icons mean.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    ascending: Boolean?,
    modifier: Modifier = Modifier,
    filter: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (filter) FreeFilterColor else colors.primary
    val container = if (active) accent.copy(alpha = if (filter) 0.12f else 0.16f) else Color.Transparent
    val borderColor = if (active) {
        accent.copy(alpha = if (filter) 0.45f else 0.50f)
    } else {
        colors.onSurface.copy(alpha = 0.08f)
    }
    val contentColor = when {
        !active -> colors.onSurfaceVariant
        filter -> FreeFilterColor
        else -> colors.primary
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(container)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .handCursor()
                .clickable(onClick = onClick)
                .semantics { selected = active },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            if (ascending != null) {
                Icon(
                    imageVector = if (ascending) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 3.dp, bottom = 2.dp)
                        .size(12.dp),
                )
            }
        }
    }
}

private val FreeFilterColor = Color(0xFF6EE7A0)

private fun formatPricePerMillion(price: Double): String {
    val factor = if (price >= 1) 100L else 1000L
    val scaled = (price * factor + 0.5).toLong()
    val whole = scaled / factor
    val fraction = (scaled % factor).toString().padStart(factor.toString().length - 1, '0')
    return "$whole.$fraction".trimEnd('0').trimEnd('.')
}

@Composable
private fun FreeTierBadge(isSelected: Boolean) {
    val background = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        Color(0xFF1B5E20)
    }
    val content = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.White
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = background,
    ) {
        Text(
            text = stringResource(Res.string.model_free_badge),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
