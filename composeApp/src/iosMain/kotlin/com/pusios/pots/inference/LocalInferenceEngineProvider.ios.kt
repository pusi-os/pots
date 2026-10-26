package com.pusios.pots.inference

actual fun createLocalInferenceEngine(): LocalInferenceEngine? = IosLiteRTInferenceEngine()
