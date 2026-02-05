package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout

class InstallDialogView(context: Context, attrs: AttributeSet? = null) :
    ConstraintLayout(context, attrs) {

    val mainActivity = context as MainActivity
    private var installMessageText: TextView
    private var installGooglePlayButton: ImageView
    private var installAppUrlText: TextView
    private var installMessageOkButton: Button

    init {
        inflate(context, R.layout.install_dialog, this)

        installMessageText = findViewById(R.id.installMessageText)
        installGooglePlayButton = findViewById(R.id.google_play_button)
        installAppUrlText = findViewById(R.id.installAppUrlText)
        installMessageOkButton = findViewById(R.id.installMessageOkButton)
        installMessageOkButton.setOnClickListener({
            installMessageOkClicked()
        })

    }

    fun load(closeable: Boolean, message: String) {

        if (closeable) {
            installMessageOkButton.visibility = VISIBLE
        }
        else {
            installMessageOkButton.visibility = GONE
        }

        installMessageText.text = message
        installAppUrlText.visibility = GONE
    }

    fun installMessageOkClicked() {
        mainActivity.closeInstallDialog()
    }

}
