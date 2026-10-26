@file:OptIn(ExperimentalMaterial3Api::class)

package com.pusios.pots.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.handCursor

@Composable
fun PotsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.handCursor(),
        valueRange = valueRange,
        steps = steps,
        colors = potsSliderColors(),
        thumb = { PotsSliderThumb() },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                colors = potsSliderTrackColors(),
                drawStopIndicator = null,
                drawTick = { _, _ -> },
            )
        },
    )
}

@Composable
fun PotsRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    RangeSlider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.handCursor(),
        valueRange = valueRange,
        steps = steps,
        startThumb = { PotsSliderThumb() },
        endThumb = { PotsSliderThumb() },
        track = { rangeSliderState ->
            SliderDefaults.Track(
                rangeSliderState = rangeSliderState,
                colors = potsSliderTrackColors(),
                drawStopIndicator = null,
                drawTick = { _, _ -> },
            )
        },
    )
}

@Composable
private fun PotsSliderThumb() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

@Composable
private fun potsSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

@Composable
private fun potsSliderTrackColors() = SliderDefaults.colors(
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
)
