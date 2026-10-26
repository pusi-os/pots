package com.pusios.pots.data

import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.DrawableResource

@Immutable
data class ServiceEntry(
    val instanceId: String,
    val serviceId: String,
    val serviceName: String,
    val modelId: String,
    val icon: DrawableResource,
    /** Free presets are named for what they do; their internal model id is noise. */
    val showModelId: Boolean = true,
)

/**
 * The model picker used by chat and by Pulse. Free presets are ordinary entries backed by
 * their own service instance, so both pickers show the same target and the same model id.
 */
fun DataRepository.getAvailableServiceEntries(): List<ServiceEntry> {
    val configuredEntries = getServiceEntries()
    val currentFreeMode = getFreeMode()
    val freeIsPrimary = isFreeServicePrimary() || configuredEntries.isEmpty()
    val freeModes = (listOf(currentFreeMode) + FreeMode.entries.filter { it != currentFreeMode }).map { mode ->
        ServiceEntry(
            instanceId = mode.instanceId,
            serviceId = mode.service.id,
            serviceName = mode.displayName,
            modelId = mode.modelId,
            icon = mode.icon,
            showModelId = false,
        )
    }
    return if (freeIsPrimary) freeModes + configuredEntries else configuredEntries + freeModes
}
