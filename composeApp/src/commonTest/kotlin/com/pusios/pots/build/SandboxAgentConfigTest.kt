package com.pusios.pots.build

import com.pusios.pots.data.Service
import com.pusios.pots.data.ServiceEntry
import com.pusios.pots.testutil.FakeDataRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The provider, model, key and endpoint Pots is configured with have to reach the
 * sandbox agents without anyone retyping them — so these tests drive the mapping
 * from settings to each agent's own config file. No sandbox, no network, no key
 * that exists anywhere.
 */
class SandboxAgentConfigTest {

    private val credentials = SandboxAgentCredentials(
        providerId = "plusvibe",
        providerName = "PlusVibe",
        model = "qwen3.8-flash",
        apiKey = "sk-test-not-a-real-key",
        endpoint = "https://plusvibeapi.ru/v1",
    )

    private fun fileFor(agentId: String, path: String): String =
        SandboxAgentConfig.filesFor(agentId, credentials).single { it.path == path }.content

    // --- every agent ------------------------------------------------------

    @Test
    fun `every sandbox agent is configured from the same provider`() {
        for (agent in BuildAgents.all) {
            val files = SandboxAgentConfig.filesFor(agent.id, credentials)
            assertTrue(files.isNotEmpty(), "${agent.id} got no configuration")
            for (file in files) {
                assertTrue(file.path.isNotBlank(), "${agent.id} wrote a file with no path")
                assertTrue(file.content.isNotBlank(), "${agent.id}/${file.path} is empty")
            }
        }
    }

    @Test
    fun `the sandbox agents are Pi and Hermes`() {
        assertEquals(listOf("pi", "hermes"), BuildAgents.all.map { it.id })
        assertEquals(listOf("Pi", "Hermes"), BuildAgents.all.map { it.title })
    }

    @Test
    fun `an unknown agent and an unconfigured Pots write nothing`() {
        assertTrue(SandboxAgentConfig.filesFor("claude-code", credentials).isEmpty())
        for (agent in BuildAgents.all) {
            assertTrue(SandboxAgentConfig.filesFor(agent.id, null).isEmpty())
        }
    }

    // --- Pi ---------------------------------------------------------------

    @Test
    fun `Pi gets the provider as a custom provider with the active model`() {
        val models = Json.parseToJsonElement(fileFor(BuildAgents.PI, ".pi/agent/models.json")).jsonObject
        val provider = models["providers"]!!.jsonObject["plusvibe"]!!.jsonObject

        assertEquals("https://plusvibeapi.ru/v1", provider["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("openai-completions", provider["api"]!!.jsonPrimitive.content)
        assertEquals("sk-test-not-a-real-key", provider["apiKey"]!!.jsonPrimitive.content)
        val model = provider["models"]!!.jsonArray.single().jsonObject
        assertEquals("qwen3.8-flash", model["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Pi starts on the provider and model Pots is on`() {
        val settings = Json.parseToJsonElement(fileFor(BuildAgents.PI, ".pi/agent/settings.json")).jsonObject

        assertEquals("plusvibe", settings["defaultProvider"]!!.jsonPrimitive.content)
        assertEquals("qwen3.8-flash", settings["defaultModel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a keyless provider still leaves Pi with a usable models file`() {
        // A local Ollama is a supported provider with no key at all; Pi rejects the
        // whole file on an empty apiKey, which would take the provider, the model
        // and the endpoint down with the key.
        val keyless = credentials.copy(providerId = "openai-compatible", model = "llama3", apiKey = "")
        val models = SandboxAgentConfig.filesFor(BuildAgents.PI, keyless)
            .single { it.path == ".pi/agent/models.json" }.content
        val provider = Json.parseToJsonElement(models).jsonObject["providers"]!!.jsonObject["openai-compatible"]!!.jsonObject

        assertEquals(SandboxAgentConfig.PLACEHOLDER_API_KEY, provider["apiKey"]!!.jsonPrimitive.content)
        assertEquals("llama3", provider["models"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("https://plusvibeapi.ru/v1", provider["baseUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a blank key is a keyless provider, not a literal space`() {
        val models = SandboxAgentConfig.filesFor(BuildAgents.PI, credentials.copy(apiKey = "   "))
            .single { it.path == ".pi/agent/models.json" }.content
        val provider = Json.parseToJsonElement(models).jsonObject["providers"]!!.jsonObject["plusvibe"]!!.jsonObject

        assertEquals(SandboxAgentConfig.PLACEHOLDER_API_KEY, provider["apiKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a key that starts with a bang cannot make Pi run a command`() {
        // Pi reads a leading `!` as "run this" and a leading `$` as an env lookup,
        // both inside a JSON string. `$!` is its own escape for a literal `!`.
        assertEquals("\$!literal-bang", SandboxAgentConfig.piApiKey("!literal-bang"))
        assertEquals("\$\$ENV_REF", SandboxAgentConfig.piApiKey("\$ENV_REF"))
        assertEquals("sk-plain", SandboxAgentConfig.piApiKey("sk-plain"))
        assertEquals(SandboxAgentConfig.PLACEHOLDER_API_KEY, SandboxAgentConfig.piApiKey(""))

        val escaped = credentials.copy(apiKey = "!literal-bang")
        val provider = Json.parseToJsonElement(
            SandboxAgentConfig.filesFor(BuildAgents.PI, escaped).single { it.path == ".pi/agent/models.json" }.content,
        ).jsonObject["providers"]!!.jsonObject["plusvibe"]!!.jsonObject

        assertEquals("\$!literal-bang", provider["apiKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Pi is pointed at a second provider by rewriting the same two files`() {
        val other = credentials.copy(
            providerId = "openai-compatible",
            providerName = "OpenAI-Compatible API",
            model = "gemma3:1b",
            endpoint = "http://192.168.1.5:11434/v1",
        )
        val models = SandboxAgentConfig.filesFor(BuildAgents.PI, other)
        val providers = Json.parseToJsonElement(models.first { it.path.endsWith("models.json") }.content)
            .jsonObject["providers"]!!.jsonObject

        assertEquals(setOf("openai-compatible"), providers.keys)
        assertEquals(
            "http://192.168.1.5:11434/v1",
            providers["openai-compatible"]!!.jsonObject["baseUrl"]!!.jsonPrimitive.content,
        )
    }

    // --- Hermes -----------------------------------------------------------

    @Test
    fun `Hermes selects the provider and model Pots is on`() {
        val config = fileFor(BuildAgents.HERMES, ".hermes/config.yaml")

        assertTrue(config.contains("  default: 'qwen3.8-flash'"), config)
        assertTrue(config.contains("  provider: 'plusvibe'"), config)
        assertTrue(config.contains("  base_url: 'https://plusvibeapi.ru/v1'"), config)
        assertTrue(config.contains("  api_mode: chat_completions"), config)
        assertTrue(config.contains("  plusvibe:"), config)
        assertTrue(config.contains("    name: 'PlusVibe'"), config)
    }

    @Test
    fun `Hermes reads the key from the environment instead of the config`() {
        val config = fileFor(BuildAgents.HERMES, ".hermes/config.yaml")
        val env = fileFor(BuildAgents.HERMES, ".hermes/.env")

        assertFalse(config.contains("sk-test-not-a-real-key"), "the key leaked into config.yaml")
        assertTrue(config.contains("key_env: HERMES_CUSTOM_PLUSVIBE_API_KEY"), config)
        assertTrue(env.contains("HERMES_CUSTOM_PLUSVIBE_API_KEY=sk-test-not-a-real-key"), env)
    }

    @Test
    fun `a keyless provider still gives Hermes a key to send`() {
        val keyless = credentials.copy(providerId = "openai-compatible", model = "llama3", apiKey = "")
        val env = SandboxAgentConfig.filesFor(BuildAgents.HERMES, keyless)
            .single { it.path == ".hermes/.env" }.content
        val config = SandboxAgentConfig.filesFor(BuildAgents.HERMES, keyless)
            .single { it.path == ".hermes/config.yaml" }.content

        assertTrue(env.contains("HERMES_CUSTOM_OPENAI_COMPATIBLE_API_KEY=${SandboxAgentConfig.PLACEHOLDER_API_KEY}"), env)
        // The variable is still declared, so Hermes does not go looking for a login.
        assertTrue(config.contains("    key_env: HERMES_CUSTOM_OPENAI_COMPATIBLE_API_KEY"), config)
    }

    @Test
    fun `the key variable is named after the provider`() {
        assertEquals("HERMES_CUSTOM_PLUSVIBE_API_KEY", SandboxAgentConfig.apiKeyEnv("plusvibe"))
        assertEquals(
            "HERMES_CUSTOM_OPENAI_COMPATIBLE_API_KEY",
            SandboxAgentConfig.apiKeyEnv("openai-compatible"),
        )
        assertEquals("HERMES_CUSTOM_Z_AI_API_KEY", SandboxAgentConfig.apiKeyEnv("z.ai"))
    }

    // --- quoting ----------------------------------------------------------

    @Test
    fun `a quote in a model id cannot end the yaml scalar`() {
        val tricky = credentials.copy(model = "it's: a model", providerId = "my'provider")
        val config = SandboxAgentConfig.filesFor(BuildAgents.HERMES, tricky)
            .single { it.path == ".hermes/config.yaml" }.content

        // Single-quoted YAML: an embedded quote is doubled, so the scalar stays open
        // until its own closing quote.
        assertTrue(config.contains("  default: 'it''s: a model'"), config)
        assertTrue(config.contains("  'my''provider':"), config)
    }

    @Test
    fun `a key that is not a bare token is quoted in the env file`() {
        val odd = credentials.copy(apiKey = "sk with space#and hash")
        val env = SandboxAgentConfig.filesFor(BuildAgents.HERMES, odd)
            .single { it.path == ".hermes/.env" }.content

        assertTrue(env.contains("HERMES_CUSTOM_PLUSVIBE_API_KEY=\"sk with space#and hash\""), env)
    }

    // --- settings -> credentials ------------------------------------------

    private fun repository(vararg entries: ServiceEntry) = FakeDataRepository().apply {
        fakeServiceEntries = entries.toList()
    }

    private fun entry(service: Service, modelId: String) = ServiceEntry(
        instanceId = service.id,
        serviceId = service.id,
        serviceName = service.displayName,
        modelId = modelId,
        icon = service.icon,
    )

    @Test
    fun `the active provider comes from the first configured instance`() {
        val repository = repository(
            entry(Service.PlusVibe, "qwen3.8-flash"),
            entry(Service.LlmRouter, "gpt-5"),
        )
        repository.updateInstanceApiKey("plusvibe", "sk-live-not-real")

        val active = repository.activeSandboxCredentials()!!

        assertEquals("plusvibe", active.providerId)
        assertEquals(Service.PlusVibe.displayName, active.providerName)
        assertEquals("qwen3.8-flash", active.model)
        assertEquals("sk-live-not-real", active.apiKey)
        // A hosted provider's endpoint is its chat URL minus the endpoint path.
        assertEquals("https://plusvibeapi.ru/v1", active.endpoint)
    }

    @Test
    fun `an OpenAI-compatible instance carries its own endpoint`() {
        val repository = repository(entry(Service.OpenAICompatible, "gemma3:1b"))
        repository.updateInstanceBaseUrl("openai-compatible", "http://192.168.1.5:11434/v1/")

        val active = repository.activeSandboxCredentials()!!

        assertEquals("openai-compatible", active.providerId)
        assertEquals("http://192.168.1.5:11434/v1", active.endpoint)
    }

    @Test
    fun `an overridden endpoint wins over the provider default`() {
        val repository = repository(entry(Service.PlusVibe, "qwen3.8-flash"))
        repository.updateInstanceBaseUrl("plusvibe", "https://proxy.local/v1")

        assertEquals("https://proxy.local/v1", repository.activeSandboxCredentials()!!.endpoint)
    }

    @Test
    fun `nothing is propagated before Pots has a provider to propagate`() {
        assertNull(repository().activeSandboxCredentials())

        // The on-device model has no endpoint an agent could call.
        assertNull(repository(entry(Service.LiteRT, "gemma3:1b")).activeSandboxCredentials())

        // A configured provider with no model chosen yet would only half-configure it.
        assertNull(repository(entry(Service.PlusVibe, "")).activeSandboxCredentials())
    }
}
