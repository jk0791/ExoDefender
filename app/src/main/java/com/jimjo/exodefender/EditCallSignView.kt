package com.jimjo.exodefender

import android.content.Context
import android.os.Message
import android.text.Editable
import android.util.AttributeSet
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.jimjo.exodefender.ServerConfig.getHostServer

interface EditCallSignCaller {
    fun editCallsignChanged()
    fun editCallsignCancel()
}

class EditCallSignView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs), android.text.TextWatcher, NetworkResponseReceiver {

    val mainActivity = context as MainActivity

    lateinit var caller: EditCallSignCaller
    val txtError: TextView
    val callSignEditText: EditText
    val btnOk: Button

    val networkProgress: ProgressBar

    var currentCallsign = ""

    init {
        inflate(context, R.layout.edit_callsign_view, this)

        callSignEditText = findViewById(R.id.callSignEditText)
        callSignEditText.addTextChangedListener(this)

        txtError = findViewById(R.id.errorText)

        btnOk = findViewById<Button>(R.id.btnOk).apply {
            setOnClickListener {
                okClicked()
            }
        }

        findViewById<Button>(R.id.btnCancel).apply {
            setOnClickListener {
                caller.editCallsignCancel()
                mainActivity.closeEditCallSignView()
            }
        }

        networkProgress = findViewById(R.id.networkProgress)
        networkProgress.visibility = GONE
    }

    fun load(caller: EditCallSignCaller) {
        this.caller = caller
        if (mainActivity.callsign != null) {
            currentCallsign = mainActivity.callsign!!
            callSignEditText.setText(currentCallsign)
        }
        txtError.text = ""
        btnOk.isEnabled = false

    }

    fun okClicked() {

        networkProgress.visibility = VISIBLE
        // attempt to change the callsign on the server
        if (mainActivity.userId != null) {
            Thread({ Networker(this, getHostServer(mainActivity)).updateUser(mainActivity.userId!!, callSignEditText.text.toString()) }).start()
        }


    }

    override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

    override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
        btnOk.isEnabled = true
        txtError.text = ""

        if (callSignEditText.text.length < 4 || (callSignEditText.text.toString() == currentCallsign)) {
            btnOk.isEnabled = false
        }
        else if (callSignEditText.text.toString() == currentCallsign) {
            btnOk.isEnabled = false
        }
        // TODO set to false if any forbidden words in callsign
    }

    override fun afterTextChanged(p0: Editable?) {}


    override fun handleNetworkMessage(msg: Message) {
        networkProgress.visibility = GONE
        when (msg.what) {
            NetworkResponse.UPDATE_USER.value -> {
                println("RECEIVE NetworkResponse.UPDATE_USER")
                mainActivity.userManager.persistNewCallsign(callSignEditText.text.toString(), true)
                caller.editCallsignChanged()
                mainActivity.closeEditCallSignView()
            }
            -1 -> txtError.text = "Sorry, that callsign is already taken. Try another one!"
            -2, -3, -4 -> {
                mainActivity.adminLogView.printout("Network error: ${msg.obj}")
                txtError.text = "Sorry, a network problem occured. Perhaps try again?"
            }
        }
    }

}
