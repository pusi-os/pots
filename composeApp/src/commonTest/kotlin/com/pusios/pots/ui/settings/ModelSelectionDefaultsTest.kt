package com.pusios.pots.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSelectionDefaultsTest {

    @Test
    fun `the sheet opens on the Recommended tab for every service`() {
        assertEquals(ModelSortOption.Recommended, initialModelSortOption())
    }
}
