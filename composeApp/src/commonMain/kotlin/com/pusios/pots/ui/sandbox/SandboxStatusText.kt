package com.pusios.pots.ui.sandbox

import androidx.compose.runtime.Composable
import com.pusios.pots.SandboxStatusLabel
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.sandbox_status_base_packages
import pots.composeapp.generated.resources.sandbox_status_clock_skew
import pots.composeapp.generated.resources.sandbox_status_configuring
import pots.composeapp.generated.resources.sandbox_status_copy_failed
import pots.composeapp.generated.resources.sandbox_status_copying_files
import pots.composeapp.generated.resources.sandbox_status_copying_files_progress
import pots.composeapp.generated.resources.sandbox_status_downloading
import pots.composeapp.generated.resources.sandbox_status_error
import pots.composeapp.generated.resources.sandbox_status_expired_metadata
import pots.composeapp.generated.resources.sandbox_status_extracting
import pots.composeapp.generated.resources.sandbox_status_install_failed
import pots.composeapp.generated.resources.sandbox_status_installing
import pots.composeapp.generated.resources.sandbox_status_installing_package
import pots.composeapp.generated.resources.sandbox_status_mirror_failed
import pots.composeapp.generated.resources.sandbox_status_network_failed
import pots.composeapp.generated.resources.sandbox_status_not_installed
import pots.composeapp.generated.resources.sandbox_status_package_failed
import pots.composeapp.generated.resources.sandbox_status_ready
import pots.composeapp.generated.resources.sandbox_status_setup_failed
import pots.composeapp.generated.resources.sandbox_status_unknown_error

/**
 * Turns the sandbox's reported step into the sentence the card shows. The install
 * runs outside composition, so this is the only place that knows the wording.
 */
@Composable
internal fun sandboxStatusText(label: SandboxStatusLabel?): String = when (label) {
    null -> ""

    SandboxStatusLabel.NotInstalled -> stringResource(Res.string.sandbox_status_not_installed)

    SandboxStatusLabel.Downloading -> stringResource(Res.string.sandbox_status_downloading)

    SandboxStatusLabel.Extracting -> stringResource(Res.string.sandbox_status_extracting)

    SandboxStatusLabel.Installing -> stringResource(Res.string.sandbox_status_installing)

    SandboxStatusLabel.Configuring -> stringResource(Res.string.sandbox_status_configuring)

    SandboxStatusLabel.BasePackages -> stringResource(Res.string.sandbox_status_base_packages)

    SandboxStatusLabel.Ready -> stringResource(Res.string.sandbox_status_ready)

    is SandboxStatusLabel.InstallingPackage ->
        stringResource(Res.string.sandbox_status_installing_package, label.packageName)

    is SandboxStatusLabel.CopyingFiles -> if (label.total > 0) {
        stringResource(Res.string.sandbox_status_copying_files_progress, label.done, label.total)
    } else {
        stringResource(Res.string.sandbox_status_copying_files)
    }

    is SandboxStatusLabel.Failure -> {
        // The underlying text arrives from the OS or a download mirror untranslated,
        // and is missing altogether often enough to need a stand-in of its own.
        val detail = label.detail.ifBlank { stringResource(Res.string.sandbox_status_unknown_error) }
        when (label) {
            is SandboxStatusLabel.Failure.Setup -> stringResource(Res.string.sandbox_status_setup_failed, detail)

            is SandboxStatusLabel.Failure.Copy -> stringResource(Res.string.sandbox_status_copy_failed, detail)

            is SandboxStatusLabel.Failure.Install -> stringResource(Res.string.sandbox_status_install_failed, detail)

            is SandboxStatusLabel.Failure.Status -> stringResource(Res.string.sandbox_status_error, detail)

            is SandboxStatusLabel.Failure.Package ->
                stringResource(Res.string.sandbox_status_package_failed, label.packageName, detail)

            // Each of these ends with what the user can do about it: a package
            // index that cannot be refreshed is otherwise an install that just
            // stops, and apt's own output does not say what to fix.
            is SandboxStatusLabel.Failure.ClockSkew -> stringResource(Res.string.sandbox_status_clock_skew, detail)

            is SandboxStatusLabel.Failure.ExpiredMetadata ->
                stringResource(Res.string.sandbox_status_expired_metadata, detail)

            is SandboxStatusLabel.Failure.Mirror -> stringResource(Res.string.sandbox_status_mirror_failed, detail)

            is SandboxStatusLabel.Failure.Network -> stringResource(Res.string.sandbox_status_network_failed, detail)
        }
    }
}
