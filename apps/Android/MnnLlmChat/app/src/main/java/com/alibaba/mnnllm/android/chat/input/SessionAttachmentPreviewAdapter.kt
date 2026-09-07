package com.alibaba.mnnllm.android.chat.input

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.mnnllm.android.R

class SessionAttachmentPreviewAdapter(
    private val onRemove: (SessionAttachmentSelection) -> Unit,
    private val onRetry: (SessionAttachmentSelection) -> Unit,
    private val onCancel: (SessionAttachmentSelection) -> Unit,
    private val onViewSource: (SessionAttachmentSelection) -> Unit
) : RecyclerView.Adapter<SessionAttachmentPreviewAdapter.Holder>() {
    private val items = mutableListOf<SessionAttachmentSelection>()

    fun submitList(values: List<SessionAttachmentSelection>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_session_attachment_preview, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.attachment_name)
        private val detail: TextView = view.findViewById(R.id.attachment_detail)
        private val source: ImageView = view.findViewById(R.id.attachment_source)
        private val retry: ImageView = view.findViewById(R.id.attachment_retry)
        private val cancel: ImageView = view.findViewById(R.id.attachment_cancel)
        private val remove: ImageView = view.findViewById(R.id.attachment_remove)

        fun bind(item: SessionAttachmentSelection) {
            name.text = item.displayName
            val size = item.sizeBytes?.let(::formatSize)
                ?: itemView.context.getString(R.string.session_attachment_size_unknown)
            val statusText = itemView.context.getString(statusString(item.status), size)
            detail.text = item.errorMessage?.takeIf { it.isNotBlank() }?.let {
                itemView.context.getString(R.string.session_attachment_status_with_error, statusText, it)
            } ?: statusText
            detail.maxLines = if (item.status == SessionAttachmentUiStatus.FAILED) 2 else 1

            source.visibility = if (item.attachmentId != null) View.VISIBLE else View.GONE
            source.setOnClickListener { onViewSource(item) }

            retry.visibility = if (item.status == SessionAttachmentUiStatus.FAILED) View.VISIBLE else View.GONE
            retry.setOnClickListener { onRetry(item) }

            cancel.visibility = if (item.status == SessionAttachmentUiStatus.PROCESSING) View.VISIBLE else View.GONE
            cancel.setOnClickListener { onCancel(item) }

            remove.visibility = if (item.status == SessionAttachmentUiStatus.PROCESSING) View.GONE else View.VISIBLE
            remove.setOnClickListener { onRemove(item) }
        }
    }

    private fun statusString(status: SessionAttachmentUiStatus): Int = when (status) {
        SessionAttachmentUiStatus.SELECTED -> R.string.session_attachment_selected
        SessionAttachmentUiStatus.PROCESSING -> R.string.session_attachment_processing
        SessionAttachmentUiStatus.READY -> R.string.session_attachment_ready
        SessionAttachmentUiStatus.FAILED -> R.string.session_attachment_failed
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024L * 1024L))
        bytes >= 1024L -> "%.1f KB".format(bytes.toDouble() / 1024L)
        else -> "$bytes B"
    }
}
