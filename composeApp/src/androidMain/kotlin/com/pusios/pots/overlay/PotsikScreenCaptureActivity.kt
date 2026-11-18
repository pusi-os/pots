package com.pusios.pots.overlay

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import com.pusios.pots.ui.potsik.POTSIK_SCREEN_WHOLE_DISPLAY_SDK
import com.pusios.pots.ui.potsik.PotsikScreenCaptureRequest
import com.pusios.pots.ui.potsik.potsikScreenCaptureRequest

/**
 * The consent dialog of the screen mode, as an activity.
 *
 * `MediaProjection` consent is an `Intent` with a result, and only an activity
 * can receive a result — an overlay window cannot. This activity is invisible
 * and exists for the length of the dialog: it launches the system screen and
 * hands the answer to [PotsikScreenProjection], which starts the capture
 * service. A refusal and a tap outside the dialog are the same answer.
 */
class PotsikScreenCaptureActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            PotsikScreenProjection.grant(this, result.resultCode, data)
        } else {
            PotsikScreenProjection.onDenied()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            PotsikScreenProjection.onSessionFailed()
            finish()
            return
        }
        runCatching { consent.launch(potsikScreenCaptureIntent(manager)) }
            .onFailure {
                PotsikScreenProjection.onSessionFailed()
                finish()
            }
    }
}

/**
 * The consent intent for this OS version.
 *
 * Android 14+ offers «a single app» by default, which is the wrong thing to
 * offer for "what is on my screen", so the whole display is asked for
 * explicitly — the decision itself is [potsikScreenCaptureRequest], which is
 * pure and tested. On 13 and below the plain intent is all the platform has.
 */
internal fun potsikScreenCaptureIntent(
    manager: MediaProjectionManager,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Intent = if (potsikScreenCaptureRequest(sdkInt) == PotsikScreenCaptureRequest.WholeDisplay) {
    wholeDisplayIntent(manager)
} else {
    manager.createScreenCaptureIntent()
}

/** The API 34 config; the version was checked by [potsikScreenCaptureRequest]. */
@RequiresApi(POTSIK_SCREEN_WHOLE_DISPLAY_SDK)
private fun wholeDisplayIntent(manager: MediaProjectionManager): Intent = manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
