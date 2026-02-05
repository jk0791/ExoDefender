package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class NewUserNotice(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs), EditCallSignCaller {

    val mainActivity = context as MainActivity
    val txtCurrentCallSign: TextView

    init {
        inflate(context, R.layout.new_user_notice, this)

        txtCurrentCallSign = findViewById(R.id.txtCurrentCallsign)

        val changeButton = findViewById<Button>(R.id.btnChangeCallsign)
        changeButton.setOnClickListener {
            mainActivity.openEditCallSignView(this)
        }

        findViewById<Button>(R.id.btnNext).apply {
            setOnClickListener {
                mainActivity.openLevelByIndex(Level.LevelType.MILKRUN, 0, false)
                mainActivity.closeNewUserNotice()
            }
        }
    }

    fun load() {
        if (mainActivity.callsign != null) {
            txtCurrentCallSign.text = mainActivity.callsign!!
        }
    }

    override fun editCallsignChanged() {
        // TODO update UI
        txtCurrentCallSign.text = mainActivity.callsign!!
    }

    override fun editCallsignCancel() {}

}
