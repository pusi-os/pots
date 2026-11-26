// The agent loop's last line of defence: an Error thrown by a tool must come back as a tool result
// instead of unwinding out of executeTool and killing the process. Pots 3.21.0 died exactly this
// way — a StackOverflowError inside the ADB backend on the first connect. Cancellation keeps its
// own contract: it is still rethrown, not reported as a failure.

package com.pusios.pots.data

import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class ToolExecutorThrowableTest {

    private class FailingTool(private val failure: Throwable) : Tool {
        override val schema = ToolSchema(name = "failing_tool", description = "always throws", parameters = emptyMap())
        override val timeout: Duration = 30.minutes
        override suspend fun execute(args: Map<String, Any>): Any = throw failure
    }

    private fun executor(failure: Throwable) = ToolExecutor(toolsProvider = { listOf(FailingTool(failure)) })

    @Test
    fun `a StackOverflowError from a tool becomes an error result instead of escaping`() = runTest {
        val result = executor(StackOverflowError()).executeTool("failing_tool", "{}")
        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals(false, json.getValue("success").jsonPrimitive.boolean, result)
        assertTrue(json.getValue("error").jsonPrimitive.content.contains("StackOverflowError"), result)
    }

    @Test
    fun `an OutOfMemoryError is contained the same way and keeps its message`() = runTest {
        val result = executor(OutOfMemoryError("Java heap space")).executeTool("failing_tool", "{}")
        assertTrue(result.contains("OutOfMemoryError"), result)
        assertTrue(result.contains("Java heap space"), result)
    }

    @Test
    fun `cancellation is still rethrown, not turned into a tool result`() = runTest {
        val executor = executor(CancellationException("stop"))
        assertFailsWith<CancellationException> { executor.executeTool("failing_tool", "{}") }
    }
}
