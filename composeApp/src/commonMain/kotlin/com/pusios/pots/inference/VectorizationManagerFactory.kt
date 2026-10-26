package com.pusios.pots.inference

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.VectorizationManager

expect fun createVectorizationManager(
    localInferenceEngine: LocalInferenceEngine?,
    appSettings: AppSettings,
): VectorizationManager
