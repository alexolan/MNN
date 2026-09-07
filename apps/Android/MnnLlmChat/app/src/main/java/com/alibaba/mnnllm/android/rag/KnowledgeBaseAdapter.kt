package com.alibaba.mnnllm.android.rag

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.databinding.ItemRagDocumentBinding
import com.alibaba.mnnllm.android.databinding.ItemRagKnowledgeBaseBinding
import com.alibaba.mnnllm.android.utils.UiUtils.getThemeColor

class KnowledgeBaseAdapter(
    private val onSelect: (KnowledgeBase) -> Unit,
    private val onImport: (KnowledgeBase) -> Unit,
    private val onDeleteDocument: (RagDocument) -> Unit,
    private val onDeleteKnowledgeBase: (KnowledgeBase) -> Unit
) : ListAdapter<KnowledgeBaseRow, KnowledgeBaseAdapter.KnowledgeBaseViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KnowledgeBaseViewHolder {
        val binding = ItemRagKnowledgeBaseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return KnowledgeBaseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: KnowledgeBaseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class KnowledgeBaseViewHolder(
        private val binding: ItemRagKnowledgeBaseBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: KnowledgeBaseRow) {
            val context = binding.root.context
            binding.knowledgeBaseName.text = row.knowledgeBase.name
            binding.knowledgeBaseSummary.text = context.resources.getQuantityString(
                R.plurals.rag_document_count,
                row.documents.size,
                row.documents.size
            )
            binding.selectedIndicator.visibility = if (row.selected) View.VISIBLE else View.GONE
            binding.root.strokeColor = if (row.selected) context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
            else ContextCompat.getColor(context, android.R.color.transparent)
            binding.root.setOnClickListener { onSelect(row.knowledgeBase) }
            binding.importDocuments.setOnClickListener { onImport(row.knowledgeBase) }
            binding.deleteKnowledgeBase.setOnClickListener {
                onDeleteKnowledgeBase(row.knowledgeBase)
            }

            binding.documentContainer.removeAllViews()
            row.documents.forEach { document ->
                val documentBinding = ItemRagDocumentBinding.inflate(
                    LayoutInflater.from(context),
                    binding.documentContainer,
                    false
                )
                documentBinding.documentName.text = document.displayName
                documentBinding.documentStatus.text = statusLabel(document.status)
                documentBinding.documentStatus.setTextColor(
                    statusColor(document.status)
                )
                documentBinding.documentError.visibility =
                    if (document.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
                documentBinding.documentError.text = document.errorMessage.orEmpty()
                documentBinding.deleteDocument.setOnClickListener {
                    onDeleteDocument(document)
                }
                binding.documentContainer.addView(documentBinding.root)
            }
        }

        private fun statusLabel(status: RagDocumentStatus): String {
            return binding.root.context.getString(
                when (status) {
                    RagDocumentStatus.QUEUED -> R.string.rag_status_queued
                    RagDocumentStatus.PARSING -> R.string.rag_status_parsing
                    RagDocumentStatus.OCR -> R.string.rag_status_ocr
                    RagDocumentStatus.EMBEDDING -> R.string.rag_status_embedding
                    RagDocumentStatus.READY -> R.string.rag_status_ready
                    RagDocumentStatus.FAILED -> R.string.rag_status_failed
                }
            )
        }

        private fun statusColor(status: RagDocumentStatus): Int {
            return when (status) {
                RagDocumentStatus.READY -> binding.root.context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
                RagDocumentStatus.FAILED -> ContextCompat.getColor(binding.root.context, android.R.color.holo_red_dark)
                else -> ContextCompat.getColor(binding.root.context, android.R.color.darker_gray)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<KnowledgeBaseRow>() {
            override fun areItemsTheSame(
                oldItem: KnowledgeBaseRow,
                newItem: KnowledgeBaseRow
            ): Boolean = oldItem.knowledgeBase.id == newItem.knowledgeBase.id

            override fun areContentsTheSame(
                oldItem: KnowledgeBaseRow,
                newItem: KnowledgeBaseRow
            ): Boolean = oldItem == newItem
        }
    }
}
