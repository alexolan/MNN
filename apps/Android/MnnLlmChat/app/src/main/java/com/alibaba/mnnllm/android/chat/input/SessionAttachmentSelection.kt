package com.alibaba.mnnllm.android.chat.input

import android.net.Uri

enum class SessionAttachmentUiStatus {
    SELECTED,
    PROCESSING,
    READY,
    FAILED
}

data class SessionAttachmentSelection(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val status: SessionAttachmentUiStatus = SessionAttachmentUiStatus.SELECTED,
    val errorMessage: String? = null,
    val attachmentId: Long? = null,
    val privatePath: String? = null,
    val sha256: String? = null
)
