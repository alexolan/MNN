// Created by ruoyi.sjd on 2025/1/9.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.chat.input

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.alibaba.mnnllm.android.MnnLlmApplication
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.chat.ChatActivity
import com.alibaba.mnnllm.android.rag.RagDatabase
import com.alibaba.mnnllm.android.rag.SessionAttachmentImporter
import com.alibaba.mnnllm.android.rag.SessionAttachmentIndexingOrchestrator
import com.alibaba.mnnllm.android.utils.FileUtils
import com.alibaba.mnnllm.android.model.ModelTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class AttachmentPickerModule(private val activity: ChatActivity) {
    private val takePhotoView: View
    private val chooseImageView: View
    private val chooseVideoView: View
    private val chooseDocumentView: View
    private val selectedDocuments = mutableListOf<SessionAttachmentSelection>()
    private val documentPreviewAdapter = SessionAttachmentPreviewAdapter(
        onRemove = ::removeDocument,
        onRetry = ::retryDocument,
        onCancel = ::cancelDocument,
        onViewSource = ::viewDocumentSource
    )
    private val documentLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handleDocumentsSelected(uris)
    }

    private val attachmentPreview: ImageView
    private val imagePreviewLayout: View
    private val imagePreviewDelete: ImageView
    private val selectAttachmentLayoutParent: View

    private var imageUri: Uri? = null
    private var photoFile: File? = null
    private var callback: ImagePickCallback? = null

    private val imagePreviewRecycler: androidx.recyclerview.widget.RecyclerView
    private val imagePreviewAdapter: ImagePreviewAdapter

    init {
        takePhotoView = activity.findViewById(R.id.more_item_camera)
        chooseImageView = activity.findViewById(R.id.more_item_photo)
        chooseVideoView = activity.findViewById(R.id.more_item_video)
        chooseDocumentView = activity.findViewById(R.id.more_item_document)
        activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.session_attachment_recycler).adapter = documentPreviewAdapter
        chooseDocumentView.setOnClickListener { documentLauncher.launch(SUPPORTED_DOCUMENT_TYPES) }
        if (ModelTypeUtils.isVisualModel(activity.modelId!!)) {
            takePhotoView.setOnClickListener { v: View? -> takePhoto() }
            chooseImageView.setOnClickListener { v: View? -> chooseImageView() }
        } else {
            takePhotoView.visibility = View.GONE
            chooseImageView.visibility = View.GONE
        }
        if (ModelTypeUtils.isVideoModel(activity.modelId!!)) {
            chooseVideoView.setOnClickListener { v: View? -> chooseVideo() }
        } else {
            chooseVideoView.visibility = View.GONE
        }
        val chooseAudioView = activity.findViewById<View>(R.id.more_item_audio)
        if (ModelTypeUtils.isAudioModel(activity.modelId!!)) {
            chooseAudioView.setOnClickListener { v: View? -> chooseAudio() }
        } else {
            chooseAudioView.visibility = View.GONE
        }
        val voiceChatView = activity.findViewById<View>(R.id.more_item_voice_chat)
        //disable temporary
        voiceChatView.visibility = View.GONE
        attachmentPreview = activity.findViewById(R.id.image_preview)
        imagePreviewLayout = activity.findViewById(R.id.image_preview_layout)
        imagePreviewRecycler = activity.findViewById(R.id.image_preview_recycler)
        imagePreviewDelete = activity.findViewById(R.id.image_preview_delete)
        selectAttachmentLayoutParent = activity.findViewById(R.id.layout_more_menu)
        imagePreviewDelete.setOnClickListener { v: View? -> deletePreviewImage() }

        imagePreviewAdapter = ImagePreviewAdapter { uri ->
            imagePreviewAdapter.removeImage(uri)
            if (imagePreviewAdapter.itemCount == 0) {
                hidePreview()
            } else {
                if (callback != null) {
                    callback!!.onAttachmentPicked(imagePreviewAdapter.getImages(), AttachmentType.Image)
                }
            }
        }
        imagePreviewRecycler.adapter = imagePreviewAdapter
        restoreSessionAttachmentsAndObserve()
    }

    private fun restoreSessionAttachmentsAndObserve() {
        val sessionId = activity.sessionId?.takeIf { it.isNotBlank() } ?: return
        activity.lifecycleScope.launch {
            while (isActive) {
                val attachments = withContext(Dispatchers.IO) {
                    val database = RagDatabase(activity.applicationContext)
                    try {
                        database.listSessionAttachments(sessionId)
                    } finally {
                        database.close()
                    }
                }
                var changed = false
                attachments.forEach { attachment ->
                    val index = selectedDocuments.indexOfFirst { it.attachmentId == attachment.id }
                    val mappedStatus = SessionAttachmentUiPolicy.status(attachment.status)
                    val restored = SessionAttachmentSelection(
                        uri = Uri.parse(attachment.sourceUri),
                        displayName = attachment.displayName,
                        mimeType = attachment.mimeType,
                        sizeBytes = attachment.sizeBytes,
                        status = mappedStatus,
                        errorMessage = attachment.errorMessage?.let(SessionAttachmentUiPolicy::actionableError),
                        attachmentId = attachment.id,
                        privatePath = attachment.privatePath,
                        sha256 = attachment.sha256
                    )
                    if (index < 0) {
                        selectedDocuments += restored
                        changed = true
                    } else if (selectedDocuments[index] != restored) {
                        selectedDocuments[index] = restored
                        changed = true
                    }
                }
                if (changed) {
                    renderDocuments()
                    notifyDocumentsChanged()
                }
                val hasProcessing = selectedDocuments.any { it.status == SessionAttachmentUiStatus.PROCESSING }
                delay(if (hasProcessing) ATTACHMENT_STATUS_POLL_MS else ATTACHMENT_IDLE_POLL_MS)
            }
        }
    }

    private fun deletePreviewImage() {
        if (imageUri != null) {
            if (photoFile != null) {
                photoFile!!.delete()
                photoFile = null
            }
            imageUri = null
        }
        showAttachmentLayout()
        hidePreview()
    }

    private fun hidePreview() {
        imagePreviewLayout.visibility = View.GONE
        imagePreviewRecycler.visibility = View.GONE
        imagePreviewDelete.visibility = View.GONE
        if (callback != null) {
            callback!!.onAttachmentRemoved()
        }
    }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("audio/x-wav")
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            activity.startActivityForResult(
                Intent.createChooser(intent, activity.getString(R.string.select_wav_file)),
                REQUEST_CODE_SELECT_WAV
            )
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(this.activity, R.string.file_manager_required, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun startVoiceChat() {
        // Hide the attachment menu
        hideAttachmentLayout()
        
        // Start the voice chat fragment
        activity.startVoiceChat()
    }

    private fun chooseImageView() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("image/*")
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        activity.startActivityForResult(
            Intent.createChooser(intent, activity.getString(R.string.select_picture)),
            REQUEST_CODE_SELECT_IMAGE,
            null
        )
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("video/*")
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            activity.startActivityForResult(
                Intent.createChooser(intent, activity.getString(R.string.select_video)),
                REQUEST_CODE_SELECT_VIDEO
            )
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(this.activity, R.string.file_manager_required, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun takePhoto() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, request it
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                // Show rationale to user
                Toast.makeText(activity, R.string.camera_permission_rationale, Toast.LENGTH_LONG).show()
            }
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_CAMERA_PERMISSION
            )
            return
        }
        
        // Permission is granted, proceed with camera
        startCameraIntent()
    }
    
    private fun startCameraIntent() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        photoFile = File(
            FileUtils.generateDestPhotoFilePath(
                this.activity,
                activity.sessionId!!
            )
        )
        imageUri = Uri.fromFile(photoFile)
        val fileProviderUri = FileProvider.getUriForFile(
            this.activity,
            activity.packageName + ".fileprovider",
            photoFile!!
        )
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileProviderUri)
        try {
            activity.startActivityForResult(cameraIntent, REQUEST_CODE_CAPTURE_IMAGE)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            Toast.makeText(activity, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No camera app found", e)
            Toast.makeText(activity, R.string.no_camera_app_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDocumentsSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size > MAX_DOCUMENTS_PER_SELECTION) {
            Toast.makeText(activity, activity.getString(R.string.session_attachment_limit, MAX_DOCUMENTS_PER_SELECTION), Toast.LENGTH_LONG).show()
        }
        val availableSlots = (MAX_DOCUMENTS_PER_SELECTION - selectedDocuments.size).coerceAtLeast(0)
        val additions = uris.distinct()
            .filter { uri -> selectedDocuments.none { it.uri == uri } }
            .take(availableSlots)
            .map(::describeDocument)
        if (additions.isEmpty()) {
            hideAttachmentLayout()
            return
        }
        selectedDocuments += additions
        renderDocuments()
        notifyDocumentsChanged()
        hideAttachmentLayout()
        importDocuments(additions)
    }

    private fun importDocuments(additions: List<SessionAttachmentSelection>) {
        val sessionId = activity.sessionId
        if (sessionId.isNullOrBlank()) {
            additions.forEach { updateDocument(it.uri, status = SessionAttachmentUiStatus.FAILED, errorMessage = "Session is unavailable") }
            return
        }
        additions.forEach { updateDocument(it.uri, status = SessionAttachmentUiStatus.PROCESSING) }
        activity.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val database = RagDatabase(activity.applicationContext)
                try {
                    SessionAttachmentImporter(activity.applicationContext, database).importAttachments(
                        sessionId = sessionId,
                        uris = additions.map { it.uri },
                        parserVersion = SESSION_ATTACHMENT_PARSER_VERSION
                    )
                } finally {
                    database.close()
                }
            }
            val runtime = (activity.application as MnnLlmApplication).ragRuntimeCoordinator
            results.forEach { result ->
                when (result) {
                    is SessionAttachmentImporter.ImportResult.Imported -> {
                        val enqueueResult = runCatching {
                            runtime.enqueueSessionAttachment(result.attachment)
                        }
                        val queued = enqueueResult.getOrNull()
                        updateDocument(
                            uri = result.uri,
                            status = when (queued) {
                                SessionAttachmentIndexingOrchestrator.EnqueueResult.AlreadyReady -> SessionAttachmentUiStatus.READY
                                SessionAttachmentIndexingOrchestrator.EnqueueResult.Queued,
                                SessionAttachmentIndexingOrchestrator.EnqueueResult.AlreadyQueued -> SessionAttachmentUiStatus.PROCESSING
                                SessionAttachmentIndexingOrchestrator.EnqueueResult.QueueFull,
                                null -> SessionAttachmentUiStatus.FAILED
                            },
                            errorMessage = when {
                                enqueueResult.isFailure -> enqueueResult.exceptionOrNull()?.message
                                queued == SessionAttachmentIndexingOrchestrator.EnqueueResult.QueueFull -> "Attachment indexing queue is full"
                                else -> null
                            },
                            attachmentId = result.attachment.id,
                            privatePath = result.attachment.privatePath,
                            sha256 = result.attachment.sha256,
                            sizeBytes = result.attachment.sizeBytes
                        )
                    }
                    is SessionAttachmentImporter.ImportResult.Duplicate -> {
                        val enqueueResult = runCatching {
                            runtime.enqueueSessionAttachment(result.existing)
                        }
                        val queued = enqueueResult.getOrNull()
                        updateDocument(
                            uri = result.uri,
                            status = when (queued) {
                                SessionAttachmentIndexingOrchestrator.EnqueueResult.AlreadyReady -> SessionAttachmentUiStatus.READY
                                SessionAttachmentIndexingOrchestrator.EnqueueResult.Queued,
                                SessionAttachmentIndexingOrchestrator.EnqueueResult.AlreadyQueued -> SessionAttachmentUiStatus.PROCESSING
                                SessionAttachmentIndexingOrchestrator.EnqueueResult.QueueFull,
                                null -> SessionAttachmentUiStatus.FAILED
                            },
                            errorMessage = when {
                                enqueueResult.isFailure -> enqueueResult.exceptionOrNull()?.message
                                queued == SessionAttachmentIndexingOrchestrator.EnqueueResult.QueueFull -> "Attachment indexing queue is full"
                                else -> null
                            },
                            attachmentId = result.existing.id,
                            privatePath = result.existing.privatePath,
                            sha256 = result.existing.sha256,
                            sizeBytes = result.existing.sizeBytes
                        )
                    }
                    is SessionAttachmentImporter.ImportResult.Failed -> updateDocument(
                        uri = result.uri,
                        status = SessionAttachmentUiStatus.FAILED,
                        errorMessage = result.reason
                    )
                }
            }
        }
    }

    private fun describeDocument(uri: Uri): SessionAttachmentSelection {
        var name = uri.lastPathSegment ?: "document"
        var size: Long? = null
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
        return SessionAttachmentSelection(uri, name, activity.contentResolver.getType(uri), size)
    }

    private fun updateDocument(
        uri: Uri,
        status: SessionAttachmentUiStatus,
        errorMessage: String? = null,
        attachmentId: Long? = null,
        privatePath: String? = null,
        sha256: String? = null,
        sizeBytes: Long? = null
    ) {
        val index = selectedDocuments.indexOfFirst { it.uri == uri }
        if (index < 0) return
        val current = selectedDocuments[index]
        selectedDocuments[index] = current.copy(
            sizeBytes = sizeBytes ?: current.sizeBytes,
            status = status,
            errorMessage = if (status == SessionAttachmentUiStatus.FAILED) {
                SessionAttachmentUiPolicy.actionableError(errorMessage)
            } else {
                errorMessage
            },
            attachmentId = attachmentId ?: current.attachmentId,
            privatePath = privatePath ?: current.privatePath,
            sha256 = sha256 ?: current.sha256
        )
        renderDocuments()
        notifyDocumentsChanged()
    }

    private fun retryDocument(item: SessionAttachmentSelection) {
        if (item.status != SessionAttachmentUiStatus.FAILED) return
        val sessionId = activity.sessionId
        if (sessionId.isNullOrBlank()) {
            updateDocument(item.uri, SessionAttachmentUiStatus.FAILED, "Session is unavailable")
            return
        }
        val attachmentId = item.attachmentId
        if (attachmentId == null) {
            importDocuments(listOf(item))
            return
        }
        updateDocument(item.uri, SessionAttachmentUiStatus.PROCESSING, errorMessage = null)
        activity.lifecycleScope.launch {
            val attachment = withContext(Dispatchers.IO) {
                val database = RagDatabase(activity.applicationContext)
                try {
                    database.getSessionAttachment(attachmentId, sessionId)
                } finally {
                    database.close()
                }
            }
            if (attachment == null) {
                updateDocument(item.uri, SessionAttachmentUiStatus.FAILED, "Attachment is no longer available")
                return@launch
            }
            val runtime = (activity.application as MnnLlmApplication).ragRuntimeCoordinator
            val result = runCatching { runtime.enqueueSessionAttachment(attachment) }.getOrNull()
            updateDocument(
                uri = item.uri,
                status = when (result) {
                    SessionAttachmentIndexingOrchestrator.EnqueueResult.AlreadyReady -> SessionAttachmentUiStatus.READY
                    SessionAttachmentIndexingOrchestrator.EnqueueResult.Queued,
                    SessionAttachmentIndexingOrchestrator.EnqueueResult.AlreadyQueued -> SessionAttachmentUiStatus.PROCESSING
                    SessionAttachmentIndexingOrchestrator.EnqueueResult.QueueFull,
                    null -> SessionAttachmentUiStatus.FAILED
                },
                errorMessage = if (result == SessionAttachmentIndexingOrchestrator.EnqueueResult.QueueFull) {
                    "Attachment indexing queue is full"
                } else null
            )
        }
    }

    private fun cancelDocument(item: SessionAttachmentSelection) {
        val attachmentId = item.attachmentId ?: return
        val sessionId = activity.sessionId?.takeIf { it.isNotBlank() } ?: return
        val runtime = (activity.application as MnnLlmApplication).ragRuntimeCoordinator
        runtime.cancelSessionAttachment(attachmentId, sessionId)
        updateDocument(
            uri = item.uri,
            status = SessionAttachmentUiStatus.FAILED,
            errorMessage = activity.getString(R.string.session_attachment_cancelled)
        )
    }

    private fun viewDocumentSource(item: SessionAttachmentSelection) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.session_attachment_source_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeDocument(item: SessionAttachmentSelection) {
        if (item.status == SessionAttachmentUiStatus.PROCESSING) return
        val attachmentId = item.attachmentId
        val sessionId = activity.sessionId
        if (attachmentId != null && !sessionId.isNullOrBlank()) {
            val runtime = (activity.application as MnnLlmApplication).ragRuntimeCoordinator
            activity.lifecycleScope.launch(Dispatchers.IO) {
                runtime.deleteSessionAttachment(attachmentId, sessionId)
            }
        }
        selectedDocuments.removeAll { it.uri == item.uri }
        renderDocuments()
        notifyDocumentsChanged()
    }

    private fun renderDocuments() {
        val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.session_attachment_recycler)
        documentPreviewAdapter.submitList(selectedDocuments)
        recycler.visibility = if (selectedDocuments.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun notifyDocumentsChanged() {
        callback?.onSessionAttachmentsChanged(selectedDocuments.toList())
    }

    fun selectedSessionAttachments(): List<SessionAttachmentSelection> = selectedDocuments.toList()

    fun setOnImagePickCallback(callback: ImagePickCallback?) {
        this.callback = callback
    }

    fun canHandleResult(requestCode: Int): Boolean {
        return requestCode >= REQUEST_CODE_SELECT_WAV && requestCode <= REQUEST_CODE_CAPTURE_IMAGE ||
               requestCode == REQUEST_CODE_CAMERA_PERMISSION ||
               requestCode == REQUEST_CODE_SELECT_VIDEO
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_CAPTURE_IMAGE) {
            if (resultCode == Activity.RESULT_OK) {
                if (imageUri != null) {
                    val imagePath = imageUri?.path
                    Log.d("ImagePath", "Image saved to: $imagePath")
                    imagePreviewAdapter.addImage(imageUri!!)
                    showImagePreview()
                }
            }
            imageUri = null
        } else if (requestCode == REQUEST_CODE_SELECT_IMAGE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val uris = mutableListOf<Uri>()
                if (data.clipData != null) {
                    val count = data.clipData!!.itemCount
                    for (i in 0 until count) {
                        uris.add(data.clipData!!.getItemAt(i).uri)
                    }
                } else if (data.data != null) {
                    uris.add(data.data!!)
                }

                if (uris.isNotEmpty()) {
                    val processedUris = mutableListOf<Uri>()
                    for (uri in uris) {
                        try {
                            val destImageFile = FileUtils.generateDestImageFilePath(
                                this.activity,
                                activity.sessionId!!
                            )
                            FileUtils.copyFileUriToPath(
                                this.activity,
                                uri,
                                destImageFile
                            )
                            processedUris.add(Uri.fromFile(File(destImageFile)))
                        } catch (e: IOException) {
                            Log.e(TAG, "get file failed ", e)
                        }
                    }
                    if (processedUris.isNotEmpty()) {
                        imagePreviewAdapter.addImages(processedUris)
                        showImagePreview()
                    }
                }
            }
        } else if (requestCode == REQUEST_CODE_SELECT_VIDEO) {
            if (resultCode == Activity.RESULT_OK) {
                val videoUri = data!!.data
                try {
                    val destVideoPath = FileUtils.generateDestVideoFilePath(
                        this.activity,
                        activity.sessionId!!
                    )
                    val destFile =
                        FileUtils.copyFileUriToPath(this.activity, videoUri!!, destVideoPath)
                    showVideoPreview(Uri.fromFile(destFile))
                } catch (e: IOException) {
                    Log.e(TAG, "get video file failed", e)
                    Toast.makeText(this.activity, R.string.video_file_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        } else if (requestCode == REQUEST_CODE_SELECT_WAV) {
            if (resultCode == Activity.RESULT_OK) {
                val audioUri = data!!.data
                try {
                    val destAudioPath = FileUtils.generateDestAudioFilePath(
                        this.activity,
                        activity.sessionId!!
                    )
                    val destFile =
                        FileUtils.copyFileUriToPath(this.activity, audioUri!!, destAudioPath)
                    showAudioPreview(Uri.fromFile(destFile))
                } catch (e: IOException) {
                    Log.e(TAG, "get audio file failed", e)
                    Toast.makeText(this.activity, R.string.audio_file_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }
    
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Camera permission granted, proceed with camera
                startCameraIntent()
            } else {
                // Camera permission denied
                Toast.makeText(activity, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAudioPreview(audioUri: Uri) {
        attachmentPreview.setImageResource(R.drawable.ic_audio_attachment)
        imagePreviewLayout.visibility = View.VISIBLE
        imagePreviewDelete.visibility = View.VISIBLE
        imagePreviewRecycler.visibility = View.GONE
        hideAttachmentLayout()
        if (callback != null) {
            callback!!.onAttachmentPicked(listOf(audioUri), AttachmentType.Audio)
        }
    }

    private fun showVideoPreview(videoUri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val thumbnail = com.alibaba.mnnllm.android.utils.VideoThumbnailUtils.generateVideoThumbnail(
                    activity, videoUri, 200, 200
                )
                withContext(Dispatchers.Main) {
                    if (thumbnail != null) {
                        attachmentPreview.setImageBitmap(thumbnail)
                    } else {
                        attachmentPreview.setImageResource(R.drawable.ic_video)
                    }
                    imagePreviewLayout.visibility = View.VISIBLE
                    imagePreviewDelete.visibility = View.VISIBLE
                    imagePreviewRecycler.visibility = View.GONE
                    hideAttachmentLayout()
                    if (callback != null) {
                        callback!!.onAttachmentPicked(listOf(videoUri), AttachmentType.Video)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    attachmentPreview.setImageResource(R.drawable.ic_video)
                    imagePreviewLayout.visibility = View.VISIBLE
                    imagePreviewDelete.visibility = View.VISIBLE
                    imagePreviewRecycler.visibility = View.GONE
                    hideAttachmentLayout()
                    if (callback != null) {
                        callback!!.onAttachmentPicked(listOf(videoUri), AttachmentType.Video)
                    }
                }
            }
        }
    }

    private fun showImagePreview() {
        imagePreviewRecycler.visibility = View.VISIBLE
        imagePreviewLayout.visibility = View.GONE
        imagePreviewDelete.visibility = View.GONE
        hideAttachmentLayout()
        if (callback != null) {
            callback!!.onAttachmentPicked(imagePreviewAdapter.getImages(), AttachmentType.Image)
        }
        imageUri = null
    }

    fun toggleAttachmentVisibility() {
        if (isShowing) {
            hideAttachmentLayout()
        } else {
            showAttachmentLayout()
        }
    }

    fun hideAttachmentLayout() {
        selectAttachmentLayoutParent.visibility = View.GONE
        if (callback != null) {
            callback!!.onAttachmentLayoutHide()
        }
    }

    private fun showAttachmentLayout() {
        selectAttachmentLayoutParent.visibility = View.VISIBLE
        if (callback != null) {
            callback!!.onAttachmentLayoutShow()
        }
    }

    val isShowing: Boolean
        get() = selectAttachmentLayoutParent.visibility == View.VISIBLE

    fun clearInput() {
        photoFile = null
        imageUri = null
        imagePreviewAdapter.clear()
        selectedDocuments.clear()
        renderDocuments()
        hidePreview()
    }

    interface ImagePickCallback {
        fun onAttachmentPicked(imageUris: List<Uri>?, audio: AttachmentType?)
        fun onAttachmentRemoved()

        fun onAttachmentLayoutShow()

        fun onAttachmentLayoutHide()
        fun onSessionAttachmentsChanged(attachments: List<SessionAttachmentSelection>) {}
    }

    enum class AttachmentType {
        Image, Audio, Video
    }

    companion object {
        const val TAG: String = "ImagePickerModule"
        var REQUEST_CODE_CAPTURE_IMAGE: Int = 100
        const val REQUEST_CODE_CAMERA_PERMISSION: Int = 101

        var REQUEST_CODE_SELECT_IMAGE: Int = 99
        var REQUEST_CODE_SELECT_VIDEO: Int = 97
        var REQUEST_CODE_SELECT_WAV: Int = 98
        const val MAX_DOCUMENTS_PER_SELECTION = 10
        const val SESSION_ATTACHMENT_PARSER_VERSION = 1
        const val ATTACHMENT_STATUS_POLL_MS = 500L
        const val ATTACHMENT_IDLE_POLL_MS = 2_000L
        val SUPPORTED_DOCUMENT_TYPES = arrayOf(
            "text/plain", "text/markdown", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/png", "image/jpeg", "image/webp"
        )
    }
}
