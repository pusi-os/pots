// The seven ADB tools the agent may call on devices in the owner's local network. Everything that
// is a decision lives here or in the helpers next to it (read-only classifier, LAN address check,
// output truncation, secret masking), so it is testable without a device; the transport is
// AdbBackend, which only moves bytes.
//
// There is deliberately no pairing tool and no mDNS scan: the six-digit code is typed by the owner
// into Settings → ADB devices, and only devices that already exist in the registry are visible to
// the agent. A scan is expensive and needs the local-network permission, which the agent must not
// be able to trigger from a chat message.

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.adb

import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import kotlinx.coroutines.CancellationException
import pots.composeapp.generated.resources.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Everything the ADB tools need. Platforms build it from Koin; tests inject fakes. */
data class AdbToolDependencies(
    val backend: AdbBackend,
    val store: AdbDeviceStore,
    val gate: AdbConfirmation,
    val apkCache: AdbApkCache,
)

/**
 * The seven ADB tools, registered in `buildAgentToolSet` behind one master toggle ([MASTER_ID], off
 * by default). Changing a device — a non-read-only shell command, an install, an uninstall — asks
 * the owner first; a device that was never paired only accepts read-only commands at all.
 */
object AdbTools {

    const val MASTER_ID = "adb_tools"

    /** How much of a command's output a tool result may carry. */
    const val SHELL_OUTPUT_LIMIT_BYTES = 8 * 1024

    /** How many ADB log lines `adb_diagnose` hands the model: enough for one attempt, small enough. */
    const val DIAGNOSE_LOG_LINES = 20

    /**
     * Budget for a tool whose call may wait in the owner confirmation gate or on a device dialog:
     * the gate's window, the longest connect deadline (a device waiting for its owner to tap
     * «Allow») and a shell command. `ToolExecutor` wraps `execute` in `withTimeout(tool.timeout)`,
     * so a shorter budget would kill the call while the owner or the device is still deciding and
     * the answer would have nowhere to go. AdbToolsTest keeps every gated budget above the window.
     */
    private val GATED_TOOL_TIMEOUT =
        (AdbGate.DEFAULT_CONFIRM_TIMEOUT_SECONDS + AdbTimeouts.CONNECT_FIRST_SEC + AdbTimeouts.SHELL_SEC + 30).seconds

    /** Names of the tool schemas [create] registers — the prompt builder keys its section on these. */
    val TOOL_NAMES = listOf(
        "adb_list_devices",
        "adb_diagnose",
        "adb_connect",
        "adb_disconnect",
        "adb_shell",
        "adb_install",
        "adb_uninstall",
    )

    /**
     * Display definitions for Settings and chat. The master switch is the set's single user
     * toggle; `isEnabled = false` is the default the Tools tab starts from, and `buildAgentToolSet`
     * gates the whole set on the same value. The individual tools are declared for chat to resolve
     * names, not as separate switches.
     */
    val toolDefinitions: List<ToolInfo> = listOf(
        ToolInfo(
            id = MASTER_ID,
            name = "ADB devices",
            description = "Master switch for the agent's ADB tools (off by default)",
            nameRes = Res.string.tool_adb_name,
            descriptionRes = Res.string.tool_adb_description,
            isEnabled = false,
        ),
        ToolInfo(
            "adb_list_devices",
            "ADB: list devices",
            "Known devices and their status",
            nameRes = Res.string.tool_adb_list_devices_name,
            descriptionRes = Res.string.tool_adb_list_devices_description,
            userToggleable = false,
        ),
        ToolInfo(
            "adb_diagnose",
            "ADB: diagnose",
            "Probe a device and show the last ADB steps",
            nameRes = Res.string.tool_adb_diagnose_name,
            descriptionRes = Res.string.tool_adb_diagnose_description,
            userToggleable = false,
        ),
        ToolInfo(
            "adb_connect",
            "ADB: connect",
            "Connect to a device on the local network",
            nameRes = Res.string.tool_adb_connect_name,
            descriptionRes = Res.string.tool_adb_connect_description,
            userToggleable = false,
        ),
        ToolInfo(
            "adb_disconnect",
            "ADB: disconnect",
            "Close the connection to a device",
            nameRes = Res.string.tool_adb_disconnect_name,
            descriptionRes = Res.string.tool_adb_disconnect_description,
            userToggleable = false,
        ),
        ToolInfo(
            "adb_shell",
            "ADB: shell",
            "Run a shell command as uid 2000, not root",
            nameRes = Res.string.tool_adb_shell_name,
            descriptionRes = Res.string.tool_adb_shell_description,
            userToggleable = false,
        ),
        ToolInfo(
            "adb_install",
            "ADB: install APK",
            "Install an APK from a URL or a path on the device",
            nameRes = Res.string.tool_adb_install_name,
            descriptionRes = Res.string.tool_adb_install_description,
            userToggleable = false,
        ),
        ToolInfo(
            "adb_uninstall",
            "ADB: uninstall",
            "Uninstall a package from the device",
            nameRes = Res.string.tool_adb_uninstall_name,
            descriptionRes = Res.string.tool_adb_uninstall_description,
            userToggleable = false,
        ),
    )

    fun create(deps: AdbToolDependencies): List<Tool> = listOf(
        listDevicesTool(deps),
        diagnoseTool(deps),
        connectTool(deps),
        disconnectTool(deps),
        shellTool(deps),
        installTool(deps),
        uninstallTool(deps),
    )

    private fun error(message: String, hint: String? = null): Map<String, Any> = buildMap {
        put("success", false)
        put("error", message)
        if (hint != null) put("hint", hint)
    }

    private fun str(args: Map<String, Any>, key: String): String? = args[key]?.toString()?.trim()?.ifEmpty { null }

    /** One sentence per failure kind, so the model can tell "pair it again" from "wrong address". */
    private fun failureText(kind: AdbErrorKind): String = when (kind) {
        AdbErrorKind.AUTHENTICATION_FAILED ->
            "the device rejected our key — it was revoked or never authorized; pair it again in Settings → ADB devices"

        AdbErrorKind.PAIRING_REQUIRED ->
            "the device requires pairing first; the owner has to pair it in Settings → ADB devices"

        AdbErrorKind.TIMEOUT -> "the operation timed out"

        AdbErrorKind.UNREACHABLE -> "nothing answers on that address"

        AdbErrorKind.NOT_ADB -> "the port is open, but nothing on it answers as ADB"

        AdbErrorKind.PERMISSION_DENIED -> "Android blocked local network access — grant the local network permission"

        AdbErrorKind.IO -> "the ADB connection failed"
    }

    private fun adbFailure(failure: AdbException): Map<String, Any> = error(failureText(failure.kind), failure.message)

    /**
     * Wording for a failure that is not an [AdbException] — an `Error` above all, which no
     * `catch (e: Exception)` ever sees. The tool result has to carry it: an ADB call that blows the
     * stack (a real 3.21.0 crash in `getDeviceName`) or runs out of memory must not take the
     * process down with it.
     */
    private fun unexpectedFailure(failure: Throwable): Map<String, Any> = error(
        "${failure::class.simpleName ?: "failure"}: ${failure.message ?: "no message"}",
        "the ADB backend failed unexpectedly; check the connection and try again",
    )

    private fun findDevice(deps: AdbToolDependencies, args: Map<String, Any>): AdbDevice? = str(args, "device_id")?.let { id -> deps.store.byId(id) }

    /** The error for a missing or unknown `device_id`, worded so the model knows which one it is. */
    private fun missingDevice(args: Map<String, Any>): Map<String, Any> {
        val id = str(args, "device_id")
        return if (id == null) {
            error("device_id is required", "call adb_list_devices to see the known devices")
        } else {
            error(
                "unknown device: $id",
                "ask the owner to add it in Settings → ADB devices; adb_list_devices shows the known ones",
            )
        }
    }

    // --- adb_list_devices -------------------------------------------------------

    private fun listDevicesTool(deps: AdbToolDependencies) = object : Tool {
        override val schema = ToolSchema(
            name = "adb_list_devices",
            description = "List the ADB devices the owner has added, with their address, whether they " +
                "were paired (trusted) and when they last answered. No network scan is performed: " +
                "adding, pairing and trusting a device is the owner's job in Settings → ADB devices.",
            parameters = emptyMap(),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val devices = deps.store.all()
            return mapOf(
                "success" to true,
                "count" to devices.size,
                "devices" to devices.map { device ->
                    mapOf(
                        "device_id" to device.id,
                        "label" to device.label,
                        "address" to device.endpoint,
                        "tls" to device.tls,
                        "trusted" to device.trusted,
                        "model" to device.lastModel,
                        "last_seen_epoch_ms" to device.lastSeenEpochMs,
                    )
                },
                "note" to "trusted=false devices only accept read-only shell commands; " +
                    "pairing and trusting a device is done by the owner in Settings → ADB devices",
            )
        }
    }

    // --- adb_diagnose -----------------------------------------------------------

    /**
     * One probe plus the tail of the step log: the answer to "why does this device not connect"
     * without a logcat session.
     *
     * It sends a bare `CNXN`, which cannot put a dialog on the device's screen (a prompt appears
     * only after a public key is presented), so it is safe to call before `adb_connect` and after a
     * failed one. No confirmation is asked: it changes nothing on the device.
     */
    private fun diagnoseTool(deps: AdbToolDependencies) = object : Tool {
        override val schema = ToolSchema(
            name = "adb_diagnose",
            description = "Check what an ADB endpoint actually answers: one CNXN probe that tells " +
                "«nothing listens», «not an ADB daemon», «waits for the owner's confirmation on the " +
                "device» and «already trusted» apart, plus the last ADB steps from the app log. Use it " +
                "after a failed adb_connect, and report its words to the owner instead of «no connection».",
            parameters = mapOf(
                "device_id" to ParameterSchema("string", "Device id from adb_list_devices", required = false),
                "endpoint" to ParameterSchema(
                    "string",
                    "host:port of any LAN address, e.g. 192.168.1.50:5555",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val deviceId = str(args, "device_id")
            val endpointText = str(args, "endpoint")
            if (deviceId == null && endpointText == null) {
                return error("device_id or endpoint is required", "call adb_list_devices to see the known devices")
            }
            val device = deviceId?.let { id -> deps.store.byId(id) }
            if (deviceId != null && device == null) return missingDevice(args)
            val host: String
            val port: Int
            if (device != null) {
                host = device.host
                port = device.port
            } else {
                val endpoint = parseAdbEndpoint(endpointText!!)
                    ?: return error("endpoint must be host:port, e.g. 192.168.1.50:5555")
                if (!isPrivateAdbHost(endpoint.host)) {
                    return error("only private/LAN addresses are allowed: ${endpoint.host}")
                }
                host = endpoint.host
                port = endpoint.port
            }
            return try {
                val probe = deps.backend.probe(host, port)
                buildMap<String, Any> {
                    put("success", true)
                    put("address", "$host:$port")
                    put("reachable", adbProbeTcpReachable(probe))
                    put("daemon", adbProbeIsDaemon(probe))
                    put("probe", adbProbeSummary(probe))
                    put("connected", deps.backend.isConnected(host, port))
                    put("log", PotsAdbLog.tail(DIAGNOSE_LOG_LINES).map { line -> line.toString() })
                    adbProbeHint(probe)?.let { hint -> put("hint", hint) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // An Error (StackOverflowError, OutOfMemoryError) must not kill the process either.
                error("diagnosis failed", failure.message ?: failure::class.simpleName.orEmpty())
            }
        }
    }

    // --- adb_connect ------------------------------------------------------------

    private fun connectTool(deps: AdbToolDependencies) = object : Tool {
        // A new endpoint waits for the owner's confirmation, then the backend's connect deadline.
        override val timeout = GATED_TOOL_TIMEOUT

        override val schema = ToolSchema(
            name = "adb_connect",
            description = "Connect to an ADB device on the local network. Pass device_id for a device " +
                "the owner already added, or endpoint (\"host:port\") for a new one — only private LAN " +
                "and loopback addresses are accepted, and a new device is stored as untrusted, so only " +
                "read-only shell commands work on it. Legacy TCP daemons (TVs, Fire TV, Android 10 and " +
                "older after `adb tcpip 5555`) are added this way. The owner is asked to confirm adding " +
                "a new address.",
            parameters = mapOf(
                "device_id" to ParameterSchema("string", "Device id from adb_list_devices", required = false),
                "endpoint" to ParameterSchema(
                    "string",
                    "host:port of a new device, e.g. 192.168.1.50:5555",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val deviceId = str(args, "device_id")
            val endpointText = str(args, "endpoint")
            if (deviceId == null && endpointText == null) {
                return error("device_id or endpoint is required", "call adb_list_devices to see the known devices")
            }
            val existing = deviceId?.let { id -> deps.store.byId(id) }
            if (deviceId != null && existing == null) {
                return error(
                    "unknown device: $deviceId",
                    "to add a device by address, pass endpoint=\"host:port\" instead",
                )
            }
            val isNew = existing == null
            val device = existing ?: run {
                val endpoint = parseAdbEndpoint(endpointText!!)
                    ?: return error("endpoint must be host:port, e.g. 192.168.1.50:5555")
                if (!isPrivateAdbHost(endpoint.host)) {
                    return error("only private/LAN addresses are allowed: ${endpoint.host}")
                }
                AdbDevice(
                    id = adbDeviceId(endpoint.host, endpoint.port),
                    label = "${endpoint.host}:${endpoint.port}",
                    host = endpoint.host,
                    port = endpoint.port,
                    // The legacy TCP daemon lives on 5555; wireless debugging advertises its own port
                    // and speaks TLS. The flag is a label, not a wire switch.
                    tls = endpoint.port != ADB_LEGACY_PORT,
                    trusted = false,
                )
            }
            if (isNew) {
                if (!deps.gate.confirm("adb_connect", "Add and connect a new device at ${device.endpoint}")) {
                    return error("denied by user")
                }
                deps.store.upsert(device)
            }
            return try {
                // firstContact widens the window for a device that does not hold our key: a TV on
                // 5555 shows its dialog every time (it never persists the key), and the owner needs
                // more than fifteen seconds to reach the remote.
                val connected = deps.backend.connect(
                    device.host,
                    device.port,
                    device.tls,
                    firstContact = !device.trusted,
                )
                if (!connected) {
                    // Should not happen — a failed connect throws with the probe's verdict — but a
                    // bare false must still carry the reason instead of "no connection".
                    val probe = deps.backend.probe(device.host, device.port)
                    error(
                        "${device.endpoint} did not confirm the connection",
                        adbProbeSummary(probe),
                    )
                } else {
                    deps.store.touch(
                        device.id,
                        lastModel = deps.backend.readProductModelOrNull(device.host, device.port),
                        lastSeenEpochMs = Clock.System.now().toEpochMilliseconds(),
                    )
                    mapOf(
                        "success" to true,
                        "device_id" to device.id,
                        "address" to device.endpoint,
                        "trusted" to device.trusted,
                    )
                }
            } catch (failure: AdbException) {
                adbFailure(failure)
            } catch (failure: CancellationException) {
                // The owner's "stop" is an outcome of its own, not a tool failure.
                throw failure
            } catch (failure: Throwable) {
                unexpectedFailure(failure)
            }
        }
    }

    // --- adb_disconnect ---------------------------------------------------------

    private fun disconnectTool(deps: AdbToolDependencies) = object : Tool {
        override val schema = ToolSchema(
            name = "adb_disconnect",
            description = "Close the open ADB connection to a device. The device stays in the owner's " +
                "list and can be connected again later.",
            parameters = mapOf(
                "device_id" to ParameterSchema("string", "Device id from adb_list_devices", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val device = findDevice(deps, args) ?: return missingDevice(args)
            return try {
                deps.backend.disconnect(device.host, device.port)
                mapOf("success" to true, "device_id" to device.id, "connected" to false)
            } catch (failure: AdbException) {
                adbFailure(failure)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                unexpectedFailure(failure)
            }
        }
    }

    // --- adb_shell --------------------------------------------------------------

    private fun shellTool(deps: AdbToolDependencies) = object : Tool {
        // A non-read-only command on a trusted device waits for the owner, then runs up to
        // AdbTimeouts.SHELL_SEC on the device: the budget covers both.
        override val timeout = GATED_TOOL_TIMEOUT

        override val schema = ToolSchema(
            name = "adb_shell",
            description = "Run a shell command on the device and return its output. The shell runs as " +
                "uid 2000 (the `shell` user), NOT root: root-only paths and some dumpsys sections are " +
                "unavailable. Output is limited to the last 8 KB and the command is killed after " +
                "${AdbTimeouts.SHELL_SEC} s. On a device that was not paired only read-only commands " +
                "(getprop, dumpsys, ls, cat, ps, id, pm list, settings get, date, logcat -d) are allowed; " +
                "anything else needs a trusted device and the owner's confirmation.",
            parameters = mapOf(
                "device_id" to ParameterSchema("string", "Device id from adb_list_devices", required = true),
                "command" to ParameterSchema("string", "Shell command, e.g. `getprop ro.product.model`", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val device = findDevice(deps, args) ?: return missingDevice(args)
            val command = str(args, "command") ?: return error("command is required")
            val readOnly = isAdbShellCommandReadOnly(command)
            if (!device.trusted && !readOnly) {
                return error(
                    "read-only commands only on an untrusted device",
                    "trust the device in Settings → ADB devices first, or use a read-only command " +
                        "(getprop, dumpsys, ls, cat, ps, id, pm list, settings get, date, logcat -d)",
                )
            }
            if (!readOnly && !deps.gate.confirm("adb_shell", "Run on ${device.label}: ${maskAdbSecrets(command)}")) {
                return error("denied by user")
            }
            return try {
                val output = deps.backend.shell(device.host, device.port, command, AdbTimeouts.SHELL_SEC)
                mapOf(
                    "success" to true,
                    "device_id" to device.id,
                    "command" to command,
                    "output" to truncateAdbShellOutput(output, SHELL_OUTPUT_LIMIT_BYTES),
                    "truncated" to (output.encodeToByteArray().size > SHELL_OUTPUT_LIMIT_BYTES),
                )
            } catch (failure: AdbException) {
                adbFailure(failure)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                unexpectedFailure(failure)
            }
        }
    }

    // --- adb_install ------------------------------------------------------------

    private fun installTool(deps: AdbToolDependencies) = object : Tool {
        // A download, a push and `pm install` in one call: 30 s would cut a large APK off mid-push.
        // 300 s also clears the confirmation window plus the backend deadlines.
        override val timeout = 300.seconds

        override val schema = ToolSchema(
            name = "adb_install",
            description = "Install an APK on the device. Pass url (http/https) to download the APK on " +
                "this phone and push it, or path for an APK that already exists on the device (e.g. " +
                "/sdcard/Download/app.apk). `pm install -r` replaces an existing version. The owner is " +
                "asked to confirm every install.",
            parameters = mapOf(
                "device_id" to ParameterSchema("string", "Device id from adb_list_devices", required = true),
                "url" to ParameterSchema("string", "http(s) URL of the APK to download", required = false),
                "path" to ParameterSchema(
                    "string",
                    "Absolute path of an APK already on the device, e.g. /sdcard/Download/app.apk",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val device = findDevice(deps, args) ?: return missingDevice(args)
            val url = str(args, "url")
            val path = str(args, "path")
            if (url == null && path == null) return error("url or path is required")
            if (url != null && path != null) return error("pass either url or path, not both")
            if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
                return error("url must start with http:// or https://")
            }
            if (path != null && !isValidAdbDevicePath(path)) {
                return error(
                    "path must be an absolute device path without shell metacharacters",
                    "e.g. /sdcard/Download/app.apk",
                )
            }
            val source = url ?: path!!
            if (!deps.gate.confirm("adb_install", "Install an APK on ${device.label} from ${maskAdbSecrets(source)}")) {
                return error("denied by user")
            }
            return try {
                val output = if (url != null) {
                    val localPath = deps.apkCache.download(url)
                    deps.backend.installApk(device.host, device.port, localPath)
                } else {
                    // The APK is already on the target: `pm install` reads it in place, no push.
                    deps.backend.shell(device.host, device.port, "pm install -r $path", AdbTimeouts.SHELL_SEC * 3)
                }
                mapOf(
                    "success" to output.contains("Success"),
                    "device_id" to device.id,
                    "output" to truncateAdbShellOutput(output, SHELL_OUTPUT_LIMIT_BYTES),
                )
            } catch (failure: AdbException) {
                adbFailure(failure)
            } catch (failure: CancellationException) {
                // ToolExecutor treats cancellation as its own outcome; it must not look like a failure.
                throw failure
            } catch (failure: Exception) {
                error("download or install failed", failure.message ?: failure::class.simpleName.orEmpty())
            } catch (failure: Throwable) {
                unexpectedFailure(failure)
            }
        }
    }

    // --- adb_uninstall ----------------------------------------------------------

    private fun uninstallTool(deps: AdbToolDependencies) = object : Tool {
        // Every uninstall waits for the owner, then runs `pm uninstall` on the device.
        override val timeout = GATED_TOOL_TIMEOUT

        override val schema = ToolSchema(
            name = "adb_uninstall",
            description = "Uninstall a package from the device (`pm uninstall`). The owner is asked to " +
                "confirm every uninstall. The package id is validated as a plain Android package name " +
                "before anything is opened, so no shell metacharacters reach the device.",
            parameters = mapOf(
                "device_id" to ParameterSchema("string", "Device id from adb_list_devices", required = true),
                "package" to ParameterSchema("string", "Android package id, e.g. com.example.app", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val device = findDevice(deps, args) ?: return missingDevice(args)
            val packageName = str(args, "package") ?: return error("package is required")
            if (!deps.gate.confirm("adb_uninstall", "Uninstall $packageName from ${device.label}")) {
                return error("denied by user")
            }
            return try {
                val output = deps.backend.uninstall(device.host, device.port, packageName)
                mapOf(
                    "success" to output.contains("Success"),
                    "device_id" to device.id,
                    "package" to packageName,
                    "output" to truncateAdbShellOutput(output, SHELL_OUTPUT_LIMIT_BYTES),
                )
            } catch (failure: AdbException) {
                // The backend already validates the package id and words the refusal; hand it over.
                error(failure.message ?: failureText(failure.kind))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                unexpectedFailure(failure)
            }
        }
    }
}
