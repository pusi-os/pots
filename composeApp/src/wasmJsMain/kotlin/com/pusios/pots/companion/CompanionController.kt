package com.pusios.pots.companion

import com.pusios.pots.data.DataRepository

actual fun createCompanionController(dataRepository: DataRepository): CompanionController = NoOpCompanionController()
