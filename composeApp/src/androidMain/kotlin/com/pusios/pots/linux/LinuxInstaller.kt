package com.pusios.pots.linux

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.File

/** Where an install has got to, in terms both feature UIs can render. */
sealed interface InstallStep {
    data class Download(val fraction: Float) : InstallStep
    data object Extract : InstallStep
    data object Configure : InstallStep

    /** One name for apk (which installs serially), the whole set for apt. */
    data class Packages(val packages: List<String>) : InstallStep
}

/**
 * A package-index failure the sandbox card has words for. Carries the class and
 * not a sentence: the installer runs outside composition, so the wording is the
 * UI's, and the class is what tells it which sentence to use.
 */
class InstallFailureException(val failure: InstallFailure) : Exception(failure.summary)

private const val LOG_TAG = "LinuxInstaller"

private const val UPDATE_TIMEOUT_SECONDS = 300L
private const val PACKAGE_TIMEOUT_SECONDS = 900L

/** Both streams plus the executor's own error, in one string for classification. */
private fun ProotResult.fullOutput(): String = listOf(stderr, stdout, error.orEmpty())
    .filter { it.isNotBlank() }
    .joinToString("\n")

/**
 * Downloads, extracts and bootstraps a rootfs. The chat sandbox and Pots Build
 * both drive this; whoever gets there first produces the install the other one
 * then finds already present.
 */
class LinuxInstaller(private val paths: LinuxPaths) {

    private val downloader = RootfsDownloader(HttpClient(OkHttp))

    /**
     * Installs [distro] into [paths] and returns the marker it wrote. Cancellable
     * between steps and during the download; a failure or cancellation removes
     * the partial rootfs so the next attempt starts clean.
     */
    suspend fun install(distro: LinuxDistro, onStep: (InstallStep) -> Unit): InstallMarker {
        val spec = DistroSpec.of(distro)
        paths.ensureLayout()
        val proot = File(paths.prootPath)
        check(proot.exists()) {
            "proot binary not found at ${paths.prootPath}. nativeLibraryDir contents: " +
                (File(paths.nativeLibDir).listFiles()?.map { it.name } ?: "empty")
        }
        paths.copyLibtalloc()

        // Wipe any partial/previous install so a retry after a failed package
        // index update (or a distro change) always re-extracts cleanly — and so
        // nothing reading the marker mid-install sees the outgoing install's.
        paths.deleteInstall()
        // deleteInstall() takes the tmp dir with it, and proot binds that as
        // /tmp — without this the install runs with no /tmp at all.
        paths.ensureLayout()

        val archive = paths.archiveFile(spec)
        try {
            onStep(InstallStep.Download(0f))
            downloader.download(spec.rootfsUrls(), archive) { onStep(InstallStep.Download(it)) }

            currentCoroutineContext().ensureActive()
            onStep(InstallStep.Extract)
            TarExtractor.extract(archive, paths.rootfsDir)
        } finally {
            archive.delete()
        }

        currentCoroutineContext().ensureActive()
        onStep(InstallStep.Configure)
        spec.configure(paths.rootfsDir)
        paths.ensureMountPoints()

        val launcher = launcherFor(spec)
        try {
            refreshPackageIndex(spec, launcher)
            currentCoroutineContext().ensureActive()
            installBasePackages(distro, launcher, onStep)
        } catch (e: Throwable) {
            // A rootfs without its base packages would skip the download on the
            // next attempt and keep failing the same way.
            paths.rootfsDir.deleteRecursively()
            throw e
        }

        val marker = InstallMarker(distro, homeOnRootfs = true)
        paths.writeMarker(marker)
        return marker
    }

    /**
     * A proot for install-time work only. A fresh install always keeps `/root` on
     * the rootfs, so there is nothing to bind over it, and no projects yet.
     */
    private fun launcherFor(spec: DistroSpec) = ProotLauncher(
        prootPath = paths.prootPath,
        libDir = paths.libDir,
        rootfsPath = paths.rootfsDir.absolutePath,
        tmpPath = paths.tmpDir.absolutePath,
        binds = emptyList(),
        extraArgs = spec.prootArgs,
        env = spec.env,
    )

    /**
     * Alpine's mirrors go down independently of the one that served the rootfs,
     * so `apk update` walks the list rewriting `repositories` until one answers.
     * Debian has a single index to refresh, and a different set of things that
     * can be wrong with it — see [refreshAptIndex].
     */
    private suspend fun refreshPackageIndex(spec: DistroSpec, launcher: ProotLauncher) {
        val updateCommand = spec.distro.packageManager.updateCommand
        if (spec !is AlpineSpec) {
            refreshAptIndex(launcher, updateCommand)
            return
        }
        var lastDetail = ""
        for (mirror in spec.mirrors) {
            currentCoroutineContext().ensureActive()
            spec.writeRepositories(paths.rootfsDir, mirror)
            val result = launcher.execute(updateCommand, timeoutSeconds = 60)
            if (result.success) return
            lastDetail = result.failureDetail()
        }
        val suffix = if (lastDetail.isNotEmpty()) ": $lastDetail" else ""
        error("`$updateCommand` failed on all Alpine mirrors$suffix")
    }

    /**
     * Refreshes apt's index, and names the reason when it cannot.
     *
     * apt refuses a Release file dated in the future (`not valid yet`) and one
     * whose `Valid-Until` has passed (`is expired`). On a phone the first is the
     * usual one — the clock is set from the network and can be days behind — and
     * a wall of apt output is not an answer the user can act on. So a date
     * failure is retried once with the date checks off (what every proot
     * distribution does), and only a failure after that is reported, as a class
     * the card has a sentence for.
     *
     * No clock probe runs before the update: proot does not virtualize time, so
     * a guest `date` reads the same clock as the app and can only confirm it.
     * The drift is taken from apt's own `invalid for …` instead — see
     * [AptPackageManager.clockSkewSeconds].
     */
    private suspend fun refreshAptIndex(launcher: ProotLauncher, updateCommand: String) {
        val first = launcher.execute(updateCommand, timeoutSeconds = UPDATE_TIMEOUT_SECONDS)
        if (first.success) return
        val firstOutput = first.fullOutput()
        Log.w(LOG_TAG, "`$updateCommand` failed:\n$firstOutput")
        val retryCommand = AptPackageManager.retryCommandFor(firstOutput)
            ?: throw InstallFailureException(AptPackageManager.classifyFailure(firstOutput))
        // A cancel during the first attempt must not buy a second full index pass.
        currentCoroutineContext().ensureActive()
        val retry = launcher.execute(retryCommand, timeoutSeconds = UPDATE_TIMEOUT_SECONDS)
        val retryOutput = retry.fullOutput()
        Log.w(LOG_TAG, "`$retryCommand` retry:\n$retryOutput")
        if (retry.success) {
            // Worth a line even on success: a clock this far off breaks other
            // things (TLS, package versions) that nobody would connect to it.
            AptPackageManager.clockSkewSeconds(firstOutput)?.let {
                Log.w(
                    LOG_TAG,
                    "index refreshed with the date checks off; the device clock is " +
                        "${AptPackageManager.formatClockOffset(it)} from the mirror",
                )
            }
            return
        }
        throw InstallFailureException(AptPackageManager.indexFailure(firstOutput, retryOutput))
    }

    private suspend fun installBasePackages(
        distro: LinuxDistro,
        launcher: ProotLauncher,
        onStep: (InstallStep) -> Unit,
    ) {
        val manager = distro.packageManager
        if (distro == LinuxDistro.ALPINE) {
            // apk resolves one package per call, which also gives per-package progress.
            for (pkg in distro.basePackages) {
                currentCoroutineContext().ensureActive()
                onStep(InstallStep.Packages(listOf(pkg)))
                val result = launcher.execute(
                    manager.installCommand(pkg),
                    timeoutSeconds = PACKAGE_TIMEOUT_SECONDS,
                )
                check(result.success) { "Failed to install $pkg: ${result.failureDetail(200)}" }
            }
            return
        }
        // apt resolves the whole set at once, which is both faster and the only
        // way its dependency solver sees the full picture.
        onStep(InstallStep.Packages(distro.basePackages))
        val result = launcher.execute(
            manager.installCommand(distro.basePackages),
            timeoutSeconds = PACKAGE_TIMEOUT_SECONDS,
        )
        // Classified rather than pasted whole: the card shows one line, and apt's
        // last error line is the only part a user can act on.
        if (!result.success) throw InstallFailureException(AptPackageManager.classifyFailure(result.fullOutput()))
    }

    companion object {
        /**
         * Serializes package work across features. A shared rootfs means the chat
         * sandbox's "Install Packages" and a Pots Build agent install can otherwise
         * hit the dpkg lock at the same time and both fail.
         */
        val packageLock = Mutex()
    }
}
