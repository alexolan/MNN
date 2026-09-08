package com.alibaba.mnnllm.android.rag

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.databinding.ActivityKnowledgeBaseBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages local knowledge bases and SAF-backed document imports.
 * Parsing and embedding are delegated to the application-level RAG coordinator.
 */
class KnowledgeBaseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKnowledgeBaseBinding
    private lateinit var database: RagDatabase
    private lateinit var adapter: KnowledgeBaseAdapter
    private var selectedKnowledgeBaseId: Long? = null

    private val modelDirectoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@registerForActivityResult
        lifecycleScope.launch {
            binding.progressIndicator.show()
            binding.configureModel.isEnabled = false
            binding.modelStatus.setText(R.string.rag_model_importing)
            val result = withContext(Dispatchers.IO) {
                val imported = ModelBundleImporter(applicationContext).import(treeUri)
                val runtime = (application as com.alibaba.mnnllm.android.MnnLlmApplication)
                    .ragRuntimeCoordinator
                when (imported) {
                    is ModelBundleImporter.ImportResult.Imported -> runCatching {
                        runtime.configure(imported.bundleDirectory, imported.manifestFile)
                    }.fold(
                        onSuccess = { ModelConfigurationResult.Ready(it) },
                        onFailure = { ModelConfigurationResult.Failed(it.message ?: getString(R.string.rag_model_configuration_failed)) }
                    )
                    is ModelBundleImporter.ImportResult.Reused -> {
                        val manifest = imported.manifestFile
                        runCatching { runtime.configure(imported.bundleDirectory, manifest) }.fold(
                            onSuccess = { ModelConfigurationResult.Ready(it) },
                            onFailure = { ModelConfigurationResult.Failed(it.message ?: getString(R.string.rag_model_configuration_failed)) }
                        )
                    }
                    is ModelBundleImporter.ImportResult.Failed ->
                        ModelConfigurationResult.Failed(imported.reason)
                }
            }
            binding.progressIndicator.hide()
            binding.configureModel.isEnabled = true
            when (result) {
                is ModelConfigurationResult.Ready -> {
                    binding.modelStatus.text = getString(
                        R.string.rag_model_ready,
                        result.info.embeddingModelId,
                        result.info.dimensions
                    )
                    indexQueuedDocuments()
                }
                is ModelConfigurationResult.Failed -> {
                    binding.modelStatus.text = getString(R.string.rag_model_failed, result.reason)
                    Toast.makeText(this@KnowledgeBaseActivity, result.reason, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val knowledgeBaseId = selectedKnowledgeBaseId
        if (knowledgeBaseId == null || uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            binding.progressIndicator.show()
            val results = withContext(Dispatchers.IO) {
                val importedResults = DocumentImporter(applicationContext, database).importDocuments(
                    knowledgeBaseId = knowledgeBaseId,
                    uris = uris,
                    parserVersion = PARSER_VERSION
                )
                val runtime = (application as com.alibaba.mnnllm.android.MnnLlmApplication)
                    .ragRuntimeCoordinator
                if (runtime.isConfigured()) {
                    importedResults.filterIsInstance<DocumentImporter.ImportResult.Imported>()
                        .forEach(runtime::indexImported)
                }
                importedResults
            }
            binding.progressIndicator.hide()
            val imported = results.count { it is DocumentImporter.ImportResult.Imported }
            val duplicates = results.count { it is DocumentImporter.ImportResult.Duplicate }
            val failed = results.count { it is DocumentImporter.ImportResult.Failed }
            Toast.makeText(
                this@KnowledgeBaseActivity,
                getString(R.string.rag_import_summary, imported, duplicates, failed),
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKnowledgeBaseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.rag_knowledge_bases)

        selectedKnowledgeBaseId = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getLong(KEY_SELECTED_KNOWLEDGE_BASE, NO_SELECTION)
            .takeIf { it > 0L }

        database = RagDatabase(applicationContext)
        adapter = KnowledgeBaseAdapter(
            onSelect = { knowledgeBase -> selectKnowledgeBase(knowledgeBase.id) },
            onImport = { knowledgeBase ->
                selectedKnowledgeBaseId = knowledgeBase.id
                documentPicker.launch(SUPPORTED_MIME_TYPES)
            },
            onDeleteDocument = { document -> confirmDeleteDocument(document) },
            onDeleteKnowledgeBase = { knowledgeBase -> confirmDeleteKnowledgeBase(knowledgeBase) }
        )
        binding.knowledgeBaseRecycler.layoutManager = LinearLayoutManager(this)
        binding.knowledgeBaseRecycler.adapter = adapter
        binding.addKnowledgeBase.setOnClickListener { showCreateKnowledgeBaseDialog() }
        binding.configureModel.setOnClickListener { modelDirectoryPicker.launch(null) }
        val ragPreferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        binding.knowledgeBaseOnly.isChecked = ragPreferences.getBoolean(KEY_KNOWLEDGE_BASE_ONLY, false)
        binding.knowledgeBaseOnly.setOnCheckedChangeListener { _, enabled ->
            ragPreferences.edit().putBoolean(KEY_KNOWLEDGE_BASE_ONLY, enabled).apply()
        }
        val runtime = (application as com.alibaba.mnnllm.android.MnnLlmApplication)
            .ragRuntimeCoordinator
        binding.modelStatus.text = if (runtime.isConfigured()) {
            getString(R.string.rag_model_configured)
        } else {
            getString(R.string.rag_model_failed, runtime.configurationError()
                ?: getString(R.string.rag_model_configuration_failed))
        }
        refresh()
        if (runtime.isConfigured()) {
            indexQueuedDocuments()
        }
    }

    private fun indexQueuedDocuments() {
        lifecycleScope.launch {
            binding.progressIndicator.show()
            binding.modelStatus.setText(R.string.rag_indexing_queued_documents)
            val summary = withContext(Dispatchers.IO) {
                val runtime = (application as com.alibaba.mnnllm.android.MnnLlmApplication)
                    .ragRuntimeCoordinator
                val queued = database.listKnowledgeBases()
                    .flatMap { database.listDocuments(it.id) }
                    .filter { it.status == RagDocumentStatus.QUEUED }
                var completed = 0
                var failed = 0
                queued.forEach { document ->
                    try {
                        when (runtime.indexDocument(document)) {
                            is VectorIndexingPipeline.Result.Completed -> completed++
                            is VectorIndexingPipeline.Result.Failed -> failed++
                        }
                    } catch (error: Throwable) {
                        database.updateDocumentStatus(
                            document.id,
                            RagDocumentStatus.FAILED,
                            error.message ?: error.javaClass.simpleName
                        )
                        failed++
                    }
                }
                IndexingSummary(queued.size, completed, failed)
            }
            binding.progressIndicator.hide()
            binding.modelStatus.text = getString(
                R.string.rag_indexing_summary,
                summary.total,
                summary.completed,
                summary.failed
            )
            refresh()
        }
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                database.listKnowledgeBases().map { knowledgeBase ->
                    KnowledgeBaseRow(
                        knowledgeBase = knowledgeBase,
                        documents = database.listDocuments(knowledgeBase.id),
                        selected = knowledgeBase.id == selectedKnowledgeBaseId
                    )
                }
            }
            adapter.submitList(rows)
            binding.emptyState.visibility = if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun selectKnowledgeBase(knowledgeBaseId: Long) {
        selectedKnowledgeBaseId = if (selectedKnowledgeBaseId == knowledgeBaseId) null else knowledgeBaseId
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putLong(KEY_SELECTED_KNOWLEDGE_BASE, selectedKnowledgeBaseId ?: NO_SELECTION)
            .apply()
        refresh()
    }

    private fun showCreateKnowledgeBaseDialog() {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.rag_knowledge_base_name)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rag_create_knowledge_base)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.rag_name_required, Toast.LENGTH_SHORT).show()
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val id = database.createKnowledgeBase(name)
                        withContext(Dispatchers.Main) {
                            selectedKnowledgeBaseId = id
                            refresh()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteDocument(document: RagDocument) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rag_delete_document)
            .setMessage(getString(R.string.rag_delete_document_message, document.displayName))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.deleteDocument(document.id)
                    withContext(Dispatchers.Main) { refresh() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteKnowledgeBase(knowledgeBase: KnowledgeBase) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rag_delete_knowledge_base)
            .setMessage(getString(R.string.rag_delete_knowledge_base_message, knowledgeBase.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.deleteKnowledgeBase(knowledgeBase.id)
                    withContext(Dispatchers.Main) {
                        if (selectedKnowledgeBaseId == knowledgeBase.id) selectedKnowledgeBaseId = null
                        refresh()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val PREFERENCES = "rag_preferences"
        const val KEY_SELECTED_KNOWLEDGE_BASE = "selected_knowledge_base"
        const val KEY_KNOWLEDGE_BASE_ONLY = "knowledge_base_only"
        const val NO_SELECTION = -1L
        private const val PARSER_VERSION = 1
        private val SUPPORTED_MIME_TYPES = arrayOf(
            "text/plain",
            "text/markdown",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
    }
}

private sealed class ModelConfigurationResult {
    data class Ready(val info: RagRuntimeCoordinator.RuntimeInfo) : ModelConfigurationResult()
    data class Failed(val reason: String) : ModelConfigurationResult()
}

private data class IndexingSummary(
    val total: Int,
    val completed: Int,
    val failed: Int
)

data class KnowledgeBaseRow(
    val knowledgeBase: KnowledgeBase,
    val documents: List<RagDocument>,
    val selected: Boolean
)
