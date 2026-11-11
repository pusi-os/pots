package com.pusios.pots.tools

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.Attachment
import com.pusios.pots.media.MediaJobRequest
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaLog
import com.pusios.pots.media.MediaProvider
import com.pusios.pots.media.MediaReference
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.media.PromptEnhancer
import com.pusios.pots.media.getInstanceMediaImageModelId
import com.pusios.pots.media.getInstanceMediaVideoModelId
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema

/**
 * Agent media tools. Gated by the per-tool toggles `generate_image`,
 * `generate_video`, `list_media_models`, `enhance_prompt` (see `tools.md`).
 *
 * The tools take an optional `model` override; when omitted they use the
 * per-instance image/video model chosen in Settings → Services → Media.
 *
 * `generate_image` accepts a `reference_image` for edit / image-to-image:
 * the literal `attachment` (the photo the user attached to the current chat
 * message), a public `https://` URL, or an app-storage path. The model the user
 * selected is always kept; if it cannot take a reference the call fails with a
 * clear error instead of switching models.
 */
object MediaTools {

    /** Submit then poll (3s → 30s) until terminal or budget exhausted. */
    private suspend fun pollUntilDone(
        apiKey: String,
        request: MediaJobRequest,
        provider: MediaProvider,
        maxWaits: Int,
    ): com.pusios.pots.media.MediaJobState {
        var state = provider.submitJob(apiKey, request)
        var waitMs = 3_000L
        repeat(maxWaits) {
            if (state.isTerminal) return state
            kotlinx.coroutines.delay(waitMs)
            state = provider.pollJob(apiKey, state.jobId)
            waitMs = (waitMs * 2).coerceAtMost(30_000L)
        }
        return state
    }

    const val ID_GENERATE_IMAGE = "generate_image"
    const val ID_GENERATE_VIDEO = "generate_video"
    const val ID_LIST_MODELS = "list_media_models"
    const val ID_ENHANCE_PROMPT = "enhance_prompt"

    fun toolInfos(): List<ToolInfo> = listOf(
        ToolInfo(
            id = ID_GENERATE_IMAGE,
            name = "Generate image",
            description = "Generate or edit an image with the configured media provider",
            isEnabled = true,
            userToggleable = true,
        ),
        ToolInfo(
            id = ID_GENERATE_VIDEO,
            name = "Generate video",
            description = "Generate a video with the configured media provider",
            isEnabled = true,
            userToggleable = true,
        ),
        ToolInfo(
            id = ID_LIST_MODELS,
            name = "List media models",
            description = "List image/video models with prices from the media provider",
            isEnabled = true,
            userToggleable = true,
        ),
        ToolInfo(
            id = ID_ENHANCE_PROMPT,
            name = "Enhance prompt",
            description = "Rewrite a prompt into a detailed generation prompt",
            isEnabled = true,
            userToggleable = true,
        ),
    )

    fun generateImageTool(
        appSettings: AppSettings,
        apiKey: () -> String,
        instanceId: () -> String,
        provider: MediaProvider = PlusVibeMediaProvider(),
        attachmentProvider: () -> List<Attachment> = { emptyList() },
    ): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID_GENERATE_IMAGE,
            description = "Generate an image, or edit one using a reference. Returns jobId, resultUrls and cost in RUB. " +
                "reference_image accepts: \"attachment\" (the photo the user attached to the chat message), " +
                "a public https URL, or an app-storage path. Use list_media_models if you need a model id.",
            parameters = mapOf(
                "prompt" to ParameterSchema("string", "What the image should show", true),
                "model" to ParameterSchema("string", "Image model id (optional, uses Settings default)", false),
                "aspect_ratio" to ParameterSchema("string", "1:1, 16:9, 9:16, 4:3, 3:4 (optional)", false),
                "reference_image" to ParameterSchema(
                    "string",
                    "Source image to edit: \"attachment\", public https URL, or app-storage path (optional)",
                    false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val prompt = (args["prompt"] as? String).orEmpty()
            if (prompt.isBlank()) return mapOf("success" to false, "error" to "prompt is required")
            val key = apiKey()
            val referenceArg = (args["reference_image"] as? String)?.trim().orEmpty()
            val reference = when {
                referenceArg.isEmpty() -> null

                else -> resolveReference(referenceArg, attachmentProvider)
                    ?: return mapOf(
                        "success" to false,
                        "error" to "reference_image is not available. Use \"attachment\" for the user's photo, a public https URL, or an app-storage path.",
                    )
            }
            val modelArg = (args["model"] as? String).orEmpty()
            val savedModel = modelArg.ifBlank { appSettings.getInstanceMediaImageModelId(instanceId()) }
            val model = savedModel
            if (model.isBlank()) {
                return mapOf(
                    "success" to false,
                    "error" to "No image model selected. Set one in Settings -> Services -> Media, or pass model.",
                )
            }
            if (reference != null) {
                val catalog = runCatching { provider.listModels(key) }.getOrNull().orEmpty()
                val chosen = catalog.firstOrNull { it.id == model }
                if (chosen != null && !chosen.acceptsImageReference) {
                    MediaLog.w("generate_image: model $model does not accept a reference image")
                    return mapOf(
                        "success" to false,
                        "error" to "Model $model does not accept a reference image. Ask the user to pick a reference-capable model in the Media studio, or omit reference_image.",
                    )
                }
            }
            val opts = mutableMapOf<String, String>()
            (args["aspect_ratio"] as? String)?.let { opts["aspect_ratio"] = it }
            MediaLog.i("generate_image: model=$model ref=${reference != null} promptLen=${prompt.length}")
            val state = pollUntilDone(
                apiKey = key,
                request = MediaJobRequest(
                    model = model,
                    prompt = prompt,
                    opts = opts,
                    references = listOfNotNull(reference),
                    idempotencyKey = PlusVibeMediaProvider.newIdempotencyKey(),
                ),
                provider = provider,
                maxWaits = 60,
            )
            val urls = state.resultUrls
            if (state.status != com.pusios.pots.media.MediaJobStatus.Success) {
                MediaLog.w("generate_image finished non-success: status=${state.status} fail=${state.failMsg}")
            }
            return mapOf(
                "success" to true,
                "jobId" to state.jobId,
                "status" to state.status.name,
                "model" to model,
                "costRub" to state.priceRub,
                "resultUrls" to urls,
                "failMsg" to (state.failMsg ?: ""),
                "markdown" to urls.firstOrNull()?.let { "![Сгенерированное изображение]($it)" }.orEmpty(),
                "hint" to "Results expire after 5 days; save them locally.",
            )
        }
    }

    /** Turns the `reference_image` argument into a provider-ready reference. */
    private fun resolveReference(raw: String, attachmentProvider: () -> List<Attachment>): MediaReference? = when {
        raw.equals("attachment", ignoreCase = true) || raw.equals("вложение", ignoreCase = true) -> {
            val image = attachmentProvider().lastOrNull { it.mimeType.startsWith("image/") && it.data.isNotBlank() }
            image?.let { MediaReference(base64DataUri = "data:${it.mimeType};base64,${it.data}") }
        }

        raw.startsWith("https://") -> MediaReference(url = raw)

        raw.startsWith("data:") -> MediaReference(base64DataUri = raw)

        else -> MediaReference(localPath = raw)
    }

    fun generateVideoTool(
        appSettings: AppSettings,
        apiKey: () -> String,
        instanceId: () -> String,
        provider: MediaProvider = PlusVibeMediaProvider(),
    ): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID_GENERATE_VIDEO,
            description = "Generate a video from a text prompt. Returns jobId for polling.",
            parameters = mapOf(
                "prompt" to ParameterSchema("string", "What the video should show", true),
                "model" to ParameterSchema("string", "Video model id (optional, uses Settings default)", false),
                "duration" to ParameterSchema("string", "Duration in seconds, e.g. 5, 10, 15 (optional)", false),
                "resolution" to ParameterSchema("string", "480p, 720p, 1080p (optional)", false),
                "image_url" to ParameterSchema("string", "Public https first-frame image URL (optional)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val prompt = (args["prompt"] as? String).orEmpty()
            if (prompt.isBlank()) return mapOf("success" to false, "error" to "prompt is required")
            val model = (args["model"] as? String).orEmpty().ifBlank {
                appSettings.getInstanceMediaVideoModelId(instanceId())
            }
            if (model.isBlank()) {
                return mapOf(
                    "success" to false,
                    "error" to "No video model selected. Set one in Settings -> Services -> Media, or pass model.",
                )
            }
            val key = apiKey()
            val opts = mutableMapOf<String, String>()
            (args["duration"] as? String)?.let { opts["duration"] = it }
            (args["resolution"] as? String)?.let { opts["resolution"] = it }
            (args["image_url"] as? String)?.let { opts["image_url"] = it }
            MediaLog.i("generate_video: model=$model opts=${opts.keys} promptLen=${prompt.length}")
            val state = pollUntilDone(
                apiKey = key,
                request = MediaJobRequest(model = model, prompt = prompt, opts = opts, idempotencyKey = PlusVibeMediaProvider.newIdempotencyKey()),
                provider = provider,
                maxWaits = 120,
            )
            val urls = state.resultUrls
            return mapOf(
                "success" to true,
                "jobId" to state.jobId,
                "status" to state.status.name,
                "model" to model,
                "costRub" to state.priceRub,
                "resultUrls" to urls,
                "failMsg" to (state.failMsg ?: ""),
                "markdown" to urls.firstOrNull()?.let { "![Сгенерированное видео]($it)" }.orEmpty(),
                "hint" to "Results expire after 5 days; save them locally.",
            )
        }
    }

    fun listModelsTool(apiKey: () -> String, provider: MediaProvider = PlusVibeMediaProvider()): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID_LIST_MODELS,
            description = "List media models with prices. Pass kind=image|video to filter.",
            parameters = mapOf(
                "kind" to ParameterSchema("string", "Optional filter: image or video", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val kindArg = (args["kind"] as? String)?.lowercase()
            val models = provider.listModels(apiKey())
            val filtered = when (kindArg) {
                "image" -> models.filter { it.kind == MediaKind.Image }
                "video" -> models.filter { it.kind == MediaKind.Video }
                else -> models
            }
            return filtered.take(120).map {
                mapOf(
                    "model" to it.id,
                    "kind" to it.kind.name.lowercase(),
                    "unit" to (it.unit ?: ""),
                    "fromRub" to (it.fromRub ?: -1.0),
                    "priceStatus" to (it.priceStatus ?: ""),
                    "note" to (it.note ?: ""),
                    "acceptsImageReference" to it.acceptsImageReference,
                    "acceptsVideoReference" to it.acceptsVideoReference,
                )
            }
        }
    }

    /** Local rewrite template; a chat-model-backed enhancer replaces this in Studio UI. */
    fun enhancePromptTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID_ENHANCE_PROMPT,
            description = "Expand a short prompt into a detailed generation prompt.",
            parameters = mapOf(
                "prompt" to ParameterSchema("string", "Short prompt to expand", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val prompt = (args["prompt"] as? String).orEmpty().trim()
            if (prompt.isBlank()) return mapOf("success" to false, "error" to "prompt is required")
            return mapOf(
                "success" to true,
                "prompt" to PromptEnhancer.clean("$prompt, detailed, photorealistic, high quality, natural lighting, sharp focus"),
            )
        }
    }
}
