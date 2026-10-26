# Local Vector Search

**Last verified:** 2026-10-27

On Android, Pots can build a private semantic index of the user's photos and documents. Embeddings are generated on-device with `embeddinggemma-2-text-vision-440m`; the index is stored in the app's private files and is not sent to a cloud service.

## Setup and indexing

In **Settings → Tools**, download the embedding model, grant access to photos or documents when prompted, then start indexing. Automatic photo and document indexing can be enabled separately. The screen shows progress, item counts and status; when the stored index is incompatible with the current model or embedding format, it is marked stale and should be rebuilt.

Photo indexing downsizes images, corrects EXIF orientation, embeds image content, and adds a small set of semantic labels. It is incremental: unchanged photos are retained, modified photos are re-embedded, and photos no longer present in MediaStore are removed. Images that cannot be read or embedded are skipped and reported rather than being inserted with a text-only substitute.

Document indexing supports text files, PDF and DOCX. Text is split into chunks; PDF pages are rendered and embedded as images (there is no OCR pass); DOCX text is extracted from the document body. Document indexing refreshes its document entries when run.

## Search behavior

The Android-only `vector_search_storage` tool searches indexed photos and documents by meaning, not just by filename. It accepts a query, optional `photo` or `document` source, a result limit, and a minimum cosine-similarity score. Results include the matched file URI and score. The tool reports index counts and stale-index information so the assistant can explain when indexing is needed.

For questions about personal photos or documents, the assistant uses this tool before attempting shell commands or manual file scans. Embeddings and the local index stay on the device; the chat query and selected results are handled through the configured chat provider.

## Storage compatibility

The index records its schema, embedding model, vector dimensions, prompt version and image-token configuration. When these no longer match the running app, the incompatible store is discarded and the index is marked stale rather than returning results produced by a different embedding space. Reindex photos and documents after that status appears.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/kotlin/com/pusios/pots/data/VectorizationManager.kt` | Platform-neutral indexing and search contract |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/inference/VectorizationManager.android.kt` | On-device embeddings, Android media access, indexing and the versioned private store |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/tools/VectorSearchTool.kt` | Search tool schema and result metadata |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/settings/ToolsSettings.kt` | Model download, permissions, indexing controls and progress |
