package com.pusios.pots.ui.media

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The studio's layout contract for Потсик (2026-11-14): he stands twice the
 * size of a toolbar button, so the numbers the layout feeds him are pinned
 * here rather than scattered as literals.
 */
class MediaStudioLayoutTest {

    @Test
    fun `the toolbar button keeps its compact size`() {
        assertEquals(36.dp, MediaStudioToolbarButtonSize)
    }

    @Test
    fun `potsik in the studio is twice a toolbar button`() {
        assertEquals(MediaStudioToolbarButtonSize * 2, MediaStudioPotsikSize)
        assertEquals(72.dp, MediaStudioPotsikSize)
    }
}
