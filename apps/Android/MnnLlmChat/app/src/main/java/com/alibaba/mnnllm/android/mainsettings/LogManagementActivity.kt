// Copyright (c) 2026 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.mainsettings

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.mnnllm.android.utils.TimberConfig

class LogManagementActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "应用日志"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val spacing = (8 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        statusView = TextView(this).apply {
            textSize = 16f
        }
        root.addView(statusView)

        toggleButton = Button(this).apply {
            setOnClickListener {
                TimberConfig.setFileLoggingEnabled(!TimberConfig.isFileLoggingEnabled())
                refresh()
            }
        }
        root.addView(toggleButton, marginParams(spacing))

        root.addView(Button(this).apply {
            text = "刷新日志"
            setOnClickListener { refresh() }
        }, marginParams(spacing))

        root.addView(Button(this).apply {
            text = "清除日志"
            setOnClickListener {
                val cleared = TimberConfig.clearLogs(this@LogManagementActivity)
                Toast.makeText(
                    this@LogManagementActivity,
                    if (cleared) "日志已清除" else "部分日志无法清除",
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
        }, marginParams(spacing))

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scrollView = ScrollView(this).apply {
            addView(logView)
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = spacing }
        )

        setContentView(root)
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val enabled = TimberConfig.isFileLoggingEnabled()
        statusView.text = if (enabled) "文件日志：已开启" else "文件日志：已关闭"
        toggleButton.text = if (enabled) "关闭日志" else "开启日志"

        val logs = TimberConfig.readLogs(this)
        logView.text = if (logs.isBlank()) {
            if (enabled) "暂无日志。使用应用后点击刷新。" else "日志记录未开启。"
        } else {
            logs
        }
        logView.post { (logView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun marginParams(topMargin: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }
    }
}
