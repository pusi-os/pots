package com.pusios.pots.tools

import com.pusios.pots.PotsBuildController
import com.pusios.pots.build.BuildAgents
import com.pusios.pots.build.SandboxAgentConfig
import com.pusios.pots.build.activeSandboxCredentials
import com.pusios.pots.data.DataRepository
import com.pusios.pots.linux.DEFAULT_GUEST_PATH
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import com.pusios.pots.sandbox.LinuxSandboxManager
import com.pusios.pots.sandbox.ProotExecutor
import com.pusios.pots.sandbox.SandboxState
import org.koin.java.KoinJavaComponent.inject
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_ask_sandbox_agent_description
import pots.composeapp.generated.resources.tool_ask_sandbox_agent_name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_TIMEOUT_SECONDS = 300L
private const val MAX_TIMEOUT_SECONDS = 900L
private const val PROBE_TIMEOUT_SECONDS = 30L
private const val READ_TIMEOUT_SECONDS = 30L

/** What the model is shown of the answer — the end of it, where the conclusion is. */
private const val ANSWER_TAIL_BYTES = 8_000

/** What the model is shown of a failure — the start of stderr, where the complaint is. */
private const val STDERR_HEAD_BYTES = 2_000

/**
 * Where the agent CLIs live inside the guest, ahead of the system dirs. Same order
 * Pots Build's own environment uses, so the binary found here is the one the
 * Settings card reports as installed.
 */
private const val AGENT_PATH = "/root/.local/bin:/root/.local/share/pi-node/bin:/root/.opencode/bin:" + DEFAULT_GUEST_PATH

/**
 * Hands a task to one of the coding agents Pots Build installed in the sandbox and
 * returns what it printed.
 *
 * A separate tool rather than a `execute_shell_command` call, because the shell tool
 * is capped at 60 seconds and an agent run takes minutes — and because everything
 * that makes such a run work (which binary, where the key comes from, how the output
 * is clipped, no TTY to answer prompts on) belongs in code, not in the prompt.
 */
object AskSandboxAgentTool : Tool {

    const val ID = "ask_sandbox_agent"

    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
    private val buildController: PotsBuildController by inject(PotsBuildController::class.java)
    private val dataRepository: DataRepository by inject(DataRepository::class.java)

    /**
     * A run may take the whole [MAX_TIMEOUT_SECONDS], and the executor has to outlive
     * it by enough to kill the process and report — a tool timeout equal to the run's
     * would cut the result off exactly when it is most wanted.
     */
    override val timeout: Duration = (MAX_TIMEOUT_SECONDS + 60).seconds

    override val schema: ToolSchema get() = ToolSchema(
        name = ID,
        description = """Hand a task to a coding agent installed in the Linux sandbox and return what it printed.

The agent runs ONE-SHOT and NON-INTERACTIVELY (opencode run / pi -p / hermes -z): it cannot ask you or the user anything, so the task has to be self-contained — paths, file names, and what "done" looks like. It works in the sandbox home (/root) on the provider and model Pots itself is configured with.

Cost: a run spends the user's tokens on their own provider and takes minutes. Call this only when the user explicitly asks for work to be done by one of these agents ("пусть Pi сделает…", "поручить OpenCode…"). Do not call it on your own initiative, to "check something", or in a loop over several tasks.

Agents: "opencode", "pi", "hermes". Only an agent that is actually installed can run — this tool never substitutes a different one, and says so instead. The user installs agents in Settings → Sandbox.

Returns the agent's answer (the end of its output, marked when clipped) and its exit code; a non-zero exit comes back as an error with the beginning of the agent's stderr.""",
        parameters = mapOf(
            "agent" to ParameterSchema(
                "string",
                "Which sandbox agent runs the task: \"opencode\", \"pi\" or \"hermes\". Must already be installed.",
                true,
            ),
            "task" to ParameterSchema(
                "string",
                "The task in full and self-contained: the agent cannot ask follow-up questions.",
                true,
            ),
            "continue_session" to ParameterSchema(
                "boolean",
                "Continue the agent's previous session instead of starting a new one. Honoured by OpenCode (-c); an agent without such a flag ignores it. Default false.",
                false,
            ),
            "timeout" to ParameterSchema(
                "integer",
                "Seconds the agent may run (default $DEFAULT_TIMEOUT_SECONDS, max $MAX_TIMEOUT_SECONDS).",
                false,
            ),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val agentId = (args["agent"] as? String)?.trim()?.lowercase()
            ?: return failure("'agent' is required: one of ${BuildAgents.all.joinToString { "\"${it.id}\"" }}.")
        val agent = BuildAgents.get(agentId)
            ?: return failure("Unknown agent \"$agentId\". Use one of: ${BuildAgents.all.joinToString { it.id }}.")
        val task = (args["task"] as? String)?.takeIf { it.isNotBlank() }
            ?: return failure("'task' is required.")

        if (sandboxManager.state.value !is SandboxState.Ready) {
            return failure("Linux sandbox is not installed. Set it up in Settings > Tools.")
        }

        val continueSession = args["continue_session"] as? Boolean ?: false
        val timeoutSeconds = ((args["timeout"] as? Number)?.toLong() ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        // The key travels in the environment only: argv shows up in `ps` and in
        // whatever the agent echoes, and env does not.
        val credentials = dataRepository.activeSandboxCredentials()
        val env = buildMap<String, String> {
            put("PATH", AGENT_PATH)
            if (credentials != null) {
                put(
                    SandboxAgentConfig.apiKeyEnv(credentials.providerId),
                    credentials.apiKey.ifBlank { SandboxAgentConfig.PLACEHOLDER_API_KEY },
                )
            }
        }
        val secrets = listOfNotNull(credentials?.apiKey?.takeIf { it.isNotBlank() })

        val executor = sandboxManager.createProotExecutor()
        if (!isInstalled(agent.id, agent.binary, executor, env)) {
            return failure(SandboxAgentRun.notInstalled(agent.title))
        }

        // A per-call file pair: two chats can hand tasks to the same agent at once,
        // and neither may read the other's output.
        val nonce = System.nanoTime()
        val outFile = "/tmp/pots-agent-$nonce.out"
        val errFile = "/tmp/pots-agent-$nonce.err"
        val command = SandboxAgentRun.commandLine(SandboxAgentRun.argv(agent, task, continueSession))
        val run = executor.execute(
            SandboxAgentRun.runScript(command, outFile, errFile),
            timeoutSeconds = timeoutSeconds,
            workingDir = "/root",
            extraEnv = env,
        )

        val exitCode = (run["exit_code"] as? Number)?.toInt()
        val timedOut = run["timed_out"] as? Boolean ?: false
        val stdout = drain(executor, outFile, ANSWER_TAIL_BYTES + 256, fromEnd = true, env = env)
        val stderr = drain(executor, errFile, STDERR_HEAD_BYTES + 256, fromEnd = false, env = env)

        val answer = SandboxAgentRun.answer(stdout, secrets, ANSWER_TAIL_BYTES)
        val detail = if (timedOut || exitCode != 0) {
            SandboxAgentRun.failureDetail(stderr, stdout, secrets, STDERR_HEAD_BYTES)
        } else {
            SandboxAgentRun.successNote(stderr, secrets, STDERR_HEAD_BYTES)
        }

        if (timedOut || exitCode != 0) {
            val reason = when {
                timedOut -> "${agent.title} was still running after ${timeoutSeconds}s and was stopped."
                exitCode == null -> "${agent.title} could not be started in the sandbox. ${(run["error"] as? String).orEmpty()}"
                else -> "${agent.title} exited with code $exitCode."
            }
            return mapOf(
                "success" to false,
                "agent" to agent.id,
                "error" to listOf(reason, detail).filter { it.isNotBlank() }.joinToString("\n\n"),
                "output" to answer,
                "timed_out" to timedOut,
            )
        }

        return buildMap<String, Any> {
            put("success", true)
            put("agent", agent.id)
            put("exit_code", 0)
            put("output", answer)
            put("timed_out", false)
            // Warnings on a successful run are still worth seeing; silence is not.
            if (detail.isNotBlank()) put("stderr", detail)
        }
    }

    private fun failure(message: String): Map<String, Any> = mapOf("success" to false, "error" to message)

    /**
     * Installed means "Pots Build lists it", or — when that list has not caught up,
     * it is filled asynchronously after the app starts — "the binary answers
     * `--version` in the sandbox". A binary that is merely reachable is not enough:
     * a migrated home can carry Pi's shim without the Node runtime it needs, and the
     * probe is what tells the two apart.
     */
    private fun isInstalled(agentId: String, binary: String, executor: ProotExecutor, env: Map<String, String>): Boolean {
        if (agentId in buildController.state.value.installedAgents) return true
        // [binary] is a fixed catalog name, not user input.
        val probe = executor.execute("$binary --version", timeoutSeconds = PROBE_TIMEOUT_SECONDS, workingDir = "/root", extraEnv = env)
        return probe["success"] == true
    }

    /**
     * One end of a file the run wrote, and the file removed with it: the executor
     * only ever hands back the beginning of a stream, so the slice is taken in the
     * guest, where the whole file is.
     */
    private fun drain(executor: ProotExecutor, path: String, bytes: Int, fromEnd: Boolean, env: Map<String, String>): String {
        val slice = if (fromEnd) "tail" else "head"
        val quoted = SandboxAgentRun.shellQuote(path)
        val result = executor.execute(
            "$slice -c $bytes -- $quoted; rm -f $quoted",
            timeoutSeconds = READ_TIMEOUT_SECONDS,
            workingDir = "/root",
            extraEnv = env,
        )
        return (result["stdout"] as? String).orEmpty()
    }

    val toolInfo = ToolInfo(
        id = ID,
        name = "Ask Sandbox Agent",
        description = "Hand a task to OpenCode, Pi or Hermes in the Linux sandbox",
        nameRes = Res.string.tool_ask_sandbox_agent_name,
        descriptionRes = Res.string.tool_ask_sandbox_agent_description,
        isEnabled = false,
        // Availability follows the sandbox switch plus the agent's own install, like
        // execute_shell_command: a per-tool switch here would read back nothing.
        userToggleable = false,
    )
}
