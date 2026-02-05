package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible

class AdminLog(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity
    val logScrollView: ScrollView
    val logTextView: TextView

    var log = ""

    init {

        inflate(context, R.layout.admin_log, this)

        logScrollView = findViewById(R.id.logScrollView)
        logTextView = findViewById(R.id.logTextView)

        this.findViewById<Button>(R.id.closeAdminButton).apply {
            setOnClickListener({ mainActivity.closeAdminLogView() })
        }
    }

    fun clearLog() {
        log = ""
        logTextView.text = ""
    }

    fun printout(message: String) {
        println(message)
        log += message + "\n"
        if (this.isVisible) {
            load()
        }
    }

    fun load() {
        logTextView.text = log
        // TODO scroll to end
        logScrollView.scrollTo(0, logTextView.height)
//        logScrollView.scrollY =
    }


}
