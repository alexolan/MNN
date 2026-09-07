package com.alibaba.mnnllm.android.rag

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File

/**
 * Owns the application-level RAG runtime and connects model validation,
 * parsing, indexing, retrieval, and prompt augmentation.
 */
class RagRuntimeCoordinator(
    context: Context,
    private val database: RagDatabase = RagDatabase(context.applicationContext)
) : Closeable {
    private val appContext = context.applicationContext
    private val attachmentRoot = File(appContext.filesDir, SessionAttachmentImporter.PRIVATE_ROOT)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val bundledInstaller = BundledEmbeddingModelInstaller(appContext)
    private var runtime: Runtime? = null
    private var lastConfigurationError: String? = null

    init {
        restorePreferredModel()
    }

    @Synchronized
    fun configure(modelBundleDirectory: File, manifestFile: File): RuntimeInfo {
        val info = configureInternal(modelBundleDirectory, manifestFile, persist = false)
        preferences.edit()
            .putString(KEY_EXTERNAL_MODEL_BUNDLE_PATH, modelBundleDirectory.canonicalPath)
            .putString(KEY_EXTERNAL_MODEL_MANIFEST_PATH, manifestFile.canonicalPath)
            .putString(KEY_MODEL_SOURCE, ModelSource.EXTERNAL.name)
            .apply()
        lastConfigurationError = null
        return info.copy(source = ModelSource.EXTERNAL)
    }

    @Synchronized
    fun useBundledModel(): RuntimeInfo = configureBundledModel(persistSelection = true)

    private fun configureBundledModel(persistSelection: Boolean): RuntimeInfo {
        val installation = installBundledWithRepair()
        val info = configureInternal(
            installation.directory,
            File(installation.directory, BundledEmbeddingModelInstaller.MANIFEST_FILE),
            persist = false
        )
        if (persistSelection) {
            preferences.edit().putString(KEY_MODEL_SOURCE, ModelSource.BUNDLED.name).apply()
        }
        lastConfigurationError = null
        return info.copy(source = ModelSource.BUNDLED)
    }

    private fun configureInternal(
        modelBundleDirectory: File,
        manifestFile: File,
        persist: Boolean
    ): RuntimeInfo {
        closeRuntime()
        val validated = ModelManifestValidator().validate(modelBundleDirectory, manifestFile)
        val model = validated.embeddingModel
        val dimensions = embeddingDimensions(model)
        val configFile = embeddingConfigFile(validated, model)
        val engine = MnnEmbeddingEngine(configFile, dimensions)
        val vectorDirectory = File(appContext.filesDir, "rag/vectors").apply { mkdirs() }
        val vectorStore = VectorStore(
            File(vectorDirectory, "${validated.manifestHash}.bin"),
            dimensions,
            validated.manifestHash
        )
        val chunker = DeterministicChunker(
            TokenCounter { text -> text.codePointCount(0, text.length).coerceAtLeast(1) },
            ChunkingConfig(maxTokens = 512, overlapTokens = 64)
        )
        val orchestrator = IndexingOrchestrator(database, chunker)
        val pipeline = VectorIndexingPipeline(database, vectorStore, engine, orchestrator)
        val sessionParsingPipeline = SessionAttachmentParsingPipeline(database, chunker)
        val sessionVectorPipeline = SessionAttachmentVectorIndexingPipeline(
            database = database,
            parsingPipeline = sessionParsingPipeline,
            vectorStore = vectorStore,
            embeddingEngine = engine
        )
        val sessionOrchestrator = SessionAttachmentIndexingOrchestrator(
            database = database,
            processor = sessionVectorPipeline
        )
        val retriever = RagRetriever(
            database,
            vectorStore,
            engine,
            config = RetrievalConfig(minimumScore = 0.45f)
        )
        val assembler = RagContextAssembler()

        runtime = Runtime(
            manifestHash = validated.manifestHash,
            dimensions = dimensions,
            engine = engine,
            vectorStore = vectorStore,
            orchestrator = orchestrator,
            pipeline = pipeline,
            sessionOrchestrator = sessionOrchestrator,
            retriever = retriever,
            assembler = assembler
        )
        sessionOrchestrator.recoverInterrupted()
        if (persist) {
            preferences.edit()
                .putString(KEY_EXTERNAL_MODEL_BUNDLE_PATH, modelBundleDirectory.canonicalPath)
                .putString(KEY_EXTERNAL_MODEL_MANIFEST_PATH, manifestFile.canonicalPath)
                .putString(KEY_MODEL_SOURCE, ModelSource.EXTERNAL.name)
                .apply()
        }
        return RuntimeInfo(validated.manifestHash, dimensions, model.id, ModelSource.EXTERNAL)
    }

    private fun restorePreferredModel() {
        val preferred = preferences.getString(KEY_MODEL_SOURCE, null)
        val externalFailure = if (preferred == ModelSource.EXTERNAL.name) restoreExternalModel() else null
        if (runtime != null) return
        val bundledFailure = runCatching {
            configureBundledModel(persistSelection = preferred != ModelSource.EXTERNAL.name)
        }.exceptionOrNull()
        if (runtime == null) {
            closeRuntime()
            lastConfigurationError = listOfNotNull(externalFailure, bundledFailure)
                .joinToString("; ") { it.message ?: it.javaClass.simpleName }
                .ifBlank { "Embedding model is unavailable" }
        }
    }

    private fun restoreExternalModel(): Throwable? {
        val bundlePath = preferences.getString(KEY_EXTERNAL_MODEL_BUNDLE_PATH, null)
            ?: return IllegalStateException("Selected external embedding model is missing")
        val manifestPath = preferences.getString(KEY_EXTERNAL_MODEL_MANIFEST_PATH, null)
            ?: return IllegalStateException("Selected external embedding manifest is missing")
        return runCatching {
            configureInternal(File(bundlePath), File(manifestPath), persist = false)
        }.exceptionOrNull()
    }

    private fun installBundledWithRepair(): BundledEmbeddingModelInstaller.Installation {
        return try {
            bundledInstaller.install()
        } catch (first: Throwable) {
            val root = File(appContext.filesDir, BundledEmbeddingModelInstaller.PRIVATE_MODEL_ROOT)
            root.listFiles()?.filter { !it.name.startsWith(".install-") }?.forEach { it.deleteRecursively() }
            try {
                bundledInstaller.install()
            } catch (second: Throwable) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    @Synchronized
    fun promptProvider(
        knowledgeBaseId: Long?,
        sessionId: String?
    ): RagPromptProvider? {
        val normalizedKnowledgeBaseId = knowledgeBaseId?.takeIf { it > 0L }
        val normalizedSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedKnowledgeBaseId == null && normalizedSessionId == null) return null
        val active = runtime ?: return null
        return RagPromptProvider { question ->
            val hits = active.retriever.retrieve(
                knowledgeBaseId = normalizedKnowledgeBaseId,
                sessionId = normalizedSessionId,
                query = question
            )
            active.assembler.assemble(question, hits)
        }
    }

    @Synchronized
    fun promptProvider(knowledgeBaseId: Long): RagPromptProvider? =
        promptProvider(knowledgeBaseId = knowledgeBaseId, sessionId = null)

    fun selectedKnowledgeBaseId(): Long? = appContext
        .getSharedPreferences(KnowledgeBaseActivity.PREFERENCES, Context.MODE_PRIVATE)
        .getLong(KnowledgeBaseActivity.KEY_SELECTED_KNOWLEDGE_BASE, KnowledgeBaseActivity.NO_SELECTION)
        .takeIf { it > 0L }

    @Synchronized
    fun indexImported(result: DocumentImporter.ImportResult.Imported): VectorIndexingPipeline.Result {
        return indexDocument(result.document)
    }

    @Synchronized
    fun indexDocument(document: RagDocument): VectorIndexingPipeline.Result {
        val active = checkNotNull(runtime) { "RAG embedding model is not configured" }
        val parser = parserFor(document)
        val prepared = active.orchestrator.prepareForEmbedding(
            document = document,
            parse = {
                appContext.contentResolver.openInputStream(Uri.parse(document.sourceUri))?.use(parser::parse)
                    ?: error("Unable to open imported document")
            }
        )
        if (prepared is IndexingOrchestrator.IndexingResult.Failed) {
            return VectorIndexingPipeline.Result.Failed(prepared.reason, prepared.cause)
        }
        return active.pipeline.indexDocument(document.id)
    }

    @Synchronized
    fun enqueueSessionAttachment(attachment: SessionAttachment): SessionAttachmentIndexingOrchestrator.EnqueueResult {
        val active = checkNotNull(runtime) { "RAG embedding model is not configured" }
        return active.sessionOrchestrator.enqueue(attachment)
    }

    @Synchronized
    fun cancelSessionAttachment(attachmentId: Long, sessionId: String): Boolean {
        val active = runtime ?: return false
        return active.sessionOrchestrator.cancelAttachment(attachmentId, sessionId)
    }

    @Synchronized
    fun deleteSessionAttachment(attachmentId: Long, sessionId: String): Boolean {
        runtime?.sessionOrchestrator?.cancelAttachment(attachmentId, sessionId)
        val privatePath = database.deleteSessionAttachment(attachmentId, sessionId) ?: return false
        check(RagResourceGovernance.deletePrivateFile(privatePath, attachmentRoot)) {
            "Attachment private copy could not be deleted"
        }
        removeEmptyAttachmentDirectories(privatePath)
        return true
    }

    @Synchronized
    fun deleteSessionAttachments(sessionId: String): Int {
        runtime?.sessionOrchestrator?.cancelSession(sessionId)
        val privatePaths = database.deleteSessionAttachments(sessionId)
        privatePaths.forEach { privatePath ->
            check(RagResourceGovernance.deletePrivateFile(privatePath, attachmentRoot)) {
                "Attachment private copy could not be deleted"
            }
            removeEmptyAttachmentDirectories(privatePath)
        }
        return privatePaths.size
    }

    private fun removeEmptyAttachmentDirectories(privatePath: String) {
        var directory = File(privatePath).parentFile
        val root = attachmentRoot.canonicalFile
        while (directory != null && directory.canonicalFile != root) {
            val canonical = directory.canonicalFile
            if (!canonical.toPath().startsWith(root.toPath()) || canonical.list()?.isNotEmpty() != false) return
            if (!canonical.delete()) return
            directory = canonical.parentFile
        }
    }

    fun isConfigured(): Boolean = synchronized(this) { runtime != null }

    fun configurationError(): String? = synchronized(this) { lastConfigurationError }

    fun activeSource(): ModelSource? = synchronized(this) {
        if (runtime == null) null else preferences.getString(KEY_MODEL_SOURCE, null)
            ?.let { runCatching { ModelSource.valueOf(it) }.getOrNull() }
    }

    @Synchronized
    override fun close() {
        closeRuntime()
        database.close()
    }

    private fun closeRuntime() {
        val active = runtime ?: return
        runtime = null
        active.sessionOrchestrator.close()
        active.vectorStore.close()
        active.engine.close()
    }

    private fun parserFor(document: RagDocument): DocumentParser {
        val extension = document.displayName.substringAfterLast('.', "").lowercase()
        return when {
            extension in setOf("md", "markdown") || document.mimeType == "text/markdown" ->
                MarkdownDocumentParser()
            extension == "docx" ||
                document.mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                DocxDocumentParser()
            extension == "txt" || document.mimeType == "text/plain" -> TextDocumentParser()
            else -> error("No indexing parser is available for ${document.displayName}")
        }
    }

    private fun embeddingDimensions(model: RagModelDefinition): Int {
        val output = model.outputs.single { it.name == "sentence_embeddings" }
        return output.shape.lastOrNull()?.takeIf { it > 0 }
            ?: error("Embedding output dimensions are missing from the manifest")
    }

    private fun embeddingConfigFile(
        bundle: ModelManifestValidator.ValidatedModelBundle,
        model: RagModelDefinition
    ): File {
        val configured = model.options[OPTION_CONFIG_PATH]
        if (!configured.isNullOrBlank()) {
            val candidate = File(bundle.directory, configured).canonicalFile
            require(candidate.isFile && candidate.toPath().startsWith(bundle.directory.canonicalFile.toPath())) {
                "Embedding config path is invalid"
            }
            return candidate
        }
        return bundle.files.firstOrNull {
            it.modelId == model.id && it.file.extension.equals("json", ignoreCase = true)
        }?.file ?: error("Embedding model config JSON is not declared")
    }

    enum class ModelSource { BUNDLED, EXTERNAL }

    data class RuntimeInfo(
        val manifestHash: String,
        val dimensions: Int,
        val embeddingModelId: String,
        val source: ModelSource
    )

    private data class Runtime(
        val manifestHash: String,
        val dimensions: Int,
        val engine: EmbeddingEngine,
        val vectorStore: VectorStore,
        val orchestrator: IndexingOrchestrator,
        val pipeline: VectorIndexingPipeline,
        val sessionOrchestrator: SessionAttachmentIndexingOrchestrator,
        val retriever: RagRetriever,
        val assembler: RagContextAssembler
    )

    companion object {
        const val OPTION_CONFIG_PATH = "configPath"
        private const val PREFERENCES = "rag_runtime"
        private const val KEY_MODEL_SOURCE = "model_source"
        private const val KEY_EXTERNAL_MODEL_BUNDLE_PATH = "model_bundle_path"
        private const val KEY_EXTERNAL_MODEL_MANIFEST_PATH = "model_manifest_path"
    }
}
