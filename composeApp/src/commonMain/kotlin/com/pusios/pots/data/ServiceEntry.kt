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
)

/**
 * The model picker used by chat and by Pulse. Every entry is a configured service instance,
 * so both pickers show the same target and the same model id.
 */
fun DataRepository.getAvailableServiceEntries(): List<ServiceEntry> = getServiceEntries()
