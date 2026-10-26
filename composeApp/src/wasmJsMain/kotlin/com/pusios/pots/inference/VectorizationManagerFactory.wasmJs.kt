package com.pusios.pots.inference

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.NoOpVectorizationManager
import com.pusios.pots.data.VectorizationManager

actual fun createVectorizationManager(
    localInferenceEngine: LocalInferenceEngine?,
    appSettings: AppSettings,
): VectorizationManager = NoOpVectorizationManager()
