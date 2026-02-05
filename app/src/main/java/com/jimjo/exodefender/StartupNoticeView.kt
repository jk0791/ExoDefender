package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit

class StartupNoticeView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    private var onOkListener: (() -> Unit)? = null
    val txtTitle: TextView
    val txtMessage: TextView

    init {

        inflate(context, R.layout.startup_notice, this)

        txtTitle = findViewById<TextView>(R.id.startupNoticeTitle)
        txtMessage = findViewById<TextView>(R.id.startupNoticeMessage)

        this.findViewById<Button>(R.id.btnStartUpNoticeOk).setOnClickListener {
            onOkListener?.invoke()
        }
    }

    fun load(title: String, message: String) {
        txtTitle.text = title
        txtMessage.text = message
    }

    fun setOnOkListener(listener: () -> Unit) {
        onOkListener = listener
    }
}