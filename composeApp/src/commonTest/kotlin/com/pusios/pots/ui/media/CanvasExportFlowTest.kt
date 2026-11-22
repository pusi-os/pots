package com.pusios.pots.ui.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export flow, walked as the user walks it.
 *
 * Nothing here draws: [ExportUiState] is the screen's own state and
 * [canvasGesture] is the canvas's own decision, so the two questions a user can
 * get stuck on — «как выйти из режима рамки» and «что теперь делает палец» — are
 * answered by the code that answers them in the app, without a composition.
 */
class CanvasExportFlowTest {

    @Test
    fun theArmedFrameToolCanBeCancelledBackToTheDialog() {
        // «Экспорт» → «Выделить на канвасе»: the dialog steps aside for the drag.
        val armed = ExportUiState().openDialog().armRegion()
        assertFalse(armed.dialogOpen, "the dialog must step aside while the frame is dragged")
        assertTrue(armed.regionTool)

        // «Отмена» on the hint band: out of the mode, back in the dialog, no frame.
        val cancelled = armed.cancelRegion()
        assertFalse(cancelled.regionTool, "the tool must be disarmed")
        assertTrue(cancelled.dialogOpen, "the export dialog must be back")
        assertNull(cancelled.region, "no frame was drawn, so there is none to export")
    }

    @Test
    fun cancellingAFrameReturnsTheDragToTheCanvas() {
        // The canvas picks its gesture from these two flags. While the tool is
        // armed a drag draws the frame; after «Отмена» the same drag must pan the
        // canvas again — otherwise the user is still stuck in the mode, only
        // with a dialog in front of it.
        val armed = ExportUiState().armRegion()
        assertEquals(CanvasGesture.RegionFrame, canvasGesture(draw = false, regionTool = armed.regionTool))

        val cancelled = armed.cancelRegion()
        assertEquals(CanvasGesture.MoveAndZoom, canvasGesture(draw = false, regionTool = cancelled.regionTool))
    }

    @Test
    fun aDrawnFrameReopensTheDialogOnIt() {
        val frame = ExportScope.Region(left = 10f, top = 20f, right = 210f, bottom = 140f, zoom = 2f)
        val drawn = ExportUiState().openDialog().armRegion().takeRegion(frame)
        assertFalse(drawn.regionTool)
        assertTrue(drawn.dialogOpen)
        assertEquals(frame, drawn.region)
    }

    @Test
    fun cancellingTheFrameToolDropsAFrameFromAnEarlierDrag() {
        // Re-arming and cancelling means «no frame»: the dialog comes back on the
        // whole canvas instead of silently keeping the previous crop.
        val old = ExportScope.Region(left = 0f, top = 0f, right = 100f, bottom = 100f)
        val cancelled = ExportUiState().takeRegion(old).armRegion().cancelRegion()
        assertNull(cancelled.region)
    }

    @Test
    fun aPanVoidsTheFrameAndThePromptDialogKeepsIt() {
        val frame = ExportScope.Region(left = 10f, top = 20f, right = 210f, bottom = 140f)
        // The frame is screen px of the view it was dragged in: a pan or a
        // recentre would leave it hanging over other content.
        assertNull(ExportUiState().takeRegion(frame).dropRegion().region)
        // «Спросить модель» hands the same frame to the prompt dialog, and the
        // export dialog is gone.
        val handover = ExportUiState().takeRegion(frame).handOverToModel()
        assertEquals(frame, handover.region)
        assertFalse(handover.dialogOpen)
        assertFalse(handover.regionTool)
    }

    @Test
    fun drawingOwnsTheDragOverEverythingElse() {
        // The draw dock and the frame tool are never on at once, but if they were
        // the pen must win: a stroke that pans the canvas is lost ink.
        assertEquals(CanvasGesture.Draw, canvasGesture(draw = true, regionTool = false))
        assertEquals(CanvasGesture.Draw, canvasGesture(draw = true, regionTool = true))
        assertEquals(CanvasGesture.MoveAndZoom, canvasGesture(draw = false, regionTool = false))
    }
}
