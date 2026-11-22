package com.pusios.pots.ui.media

// The export flow's two decisions that are not drawing: which state the dialog
// and the frame tool are in, and which gesture one finger on the canvas gets.
//
// They live here, apart from the screen and the canvas composables, because
// both are the kind of thing that goes wrong silently — a mode nobody can leave,
// a drag that selects when it should pan — and neither needs a composition to
// be checked. The screen and the canvas call these; nothing here draws.

/**
 * The export flow's state: the dialog, the armed frame tool and the frame.
 *
 * One value instead of three flags, because the three are one story: arming the
 * tool closes the dialog, a finished drag reopens it on the frame, and «Отмена»
 * reopens it with no frame at all. Every transition returns a new value, so the
 * whole flow can be walked in a test without a composition — and no state can
 * be left half-set (an armed tool with a closed dialog and no way back, which is
 * what a user who armed it by mistake used to be stuck in).
 */
internal data class ExportUiState(
    val dialogOpen: Boolean = false,
    val regionTool: Boolean = false,
    val region: ExportScope.Region? = null,
) {
    /** «Экспорт» in the layers panel: the dialog opens on the whole canvas or on the frame it still has. */
    fun openDialog(): ExportUiState = copy(dialogOpen = true)

    /** «Выделить на канвасе»: the dialog steps aside, the canvas takes the drag. */
    fun armRegion(): ExportUiState = copy(dialogOpen = false, regionTool = true)

    /** The drag finished: the dialog comes back on the frame. */
    fun takeRegion(frame: ExportScope.Region): ExportUiState = copy(dialogOpen = true, regionTool = false, region = frame)

    /**
     * «Отмена» on the armed tool: the mode is off and the dialog is back with no
     * frame. Without this the only way out of the mode was to drag a frame
     * nobody wanted — the dialog was gone and the canvas read every drag as a
     * selection.
     */
    fun cancelRegion(): ExportUiState = copy(dialogOpen = true, regionTool = false, region = null)

    /** «Спросить модель»: the export dialog hands over to the prompt dialog. */
    fun handOverToModel(): ExportUiState = copy(dialogOpen = false, regionTool = false)

    /** «Отмена» on the dialog itself. */
    fun dismissDialog(): ExportUiState = copy(dialogOpen = false)

    /**
     * The frame is in screen px of the view it was dragged in, so a pan or a
     * recentre voids it: kept, it would hang over other content and the export
     * would crop the old view.
     */
    fun dropRegion(): ExportUiState = copy(region = null)
}

/** What one finger on the canvas does. */
internal enum class CanvasGesture {
    /** Inks a stroke (the draw dock is open). */
    Draw,

    /** Drags the export frame (the region tool is armed). */
    RegionFrame,

    /** Pans and zooms; a tap selects or clears the selection. */
    MoveAndZoom,
}

/**
 * The gesture the canvas takes. The armed frame tool owns the drag — panning
 * and zooming are off while it is on, a drag would otherwise do both — and every
 * other state pans and zooms, so cancelling the tool gives the canvas back.
 */
internal fun canvasGesture(draw: Boolean, regionTool: Boolean): CanvasGesture = when {
    draw -> CanvasGesture.Draw
    regionTool -> CanvasGesture.RegionFrame
    else -> CanvasGesture.MoveAndZoom
}
