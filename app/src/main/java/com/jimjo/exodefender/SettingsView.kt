package com.jimjo.exodefender

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Switch
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit

class SettingsView(context: Context, attrs: AttributeSet? = null) :
    ConstraintLayout(context, attrs), EditCallSignCaller, TextWatcher {

    val mainActivity = context as MainActivity
    var glView: GameSurfaceView? = null

    val tranSensitivityControl: SeekBar
    val rotSensitivityControl: SeekBar
    val tranSensitivityLabel: TextView
    val rotSensitivityLabel: TextView

    val effectsVolumeControl: SeekBar
    val musicVolumeControl: SeekBar
    val btnEnableVibration: Switch
    val btnEnableRadio: Switch

    val txtCurrentCallSign: TextView


    var manualTranSensitvityChangeInProgress = false
    var manualRotSensitvityChangeInProgress = false
    var manualEffectsVolumeChangeInProgress = false
    var manualMusicVolumeChangeInProgress = false

    val effectsVolumeFactor = 1f
    val musicVolumeFactor = 1f


    var appVersionLabel: TextView

    var adminPasswordEditText: EditText
    var adminButton: Button
    var adminClickCount = 0

    init {

        inflate(context, R.layout.settings, this)


        tranSensitivityControl = findViewById(R.id.tranSensitivitySeekbar) as SeekBar
        rotSensitivityControl = findViewById(R.id.rotSensitivitySeekbar) as SeekBar
        tranSensitivityLabel = findViewById(R.id.tranSensitivityValue)
        rotSensitivityLabel = findViewById(R.id.rotSensitivityValue)

        tranSensitivityControl.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStartTrackingTouch(arg0: SeekBar) {
                manualTranSensitvityChangeInProgress = true
            }

            override fun onStopTrackingTouch(arg0: SeekBar) {
                manualTranSensitvityChangeInProgress = false
            }

            override fun onProgressChanged(arg0: SeekBar, arg1: Int, arg2: Boolean) {
                if (manualTranSensitvityChangeInProgress) {
                    tranSensitivityChanged(tranSensitivityControl.progress)
                }
            }
        })


        rotSensitivityControl.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStartTrackingTouch(arg0: SeekBar) {
                manualRotSensitvityChangeInProgress = true
            }

            override fun onStopTrackingTouch(arg0: SeekBar) {
                manualRotSensitvityChangeInProgress = false
            }

            override fun onProgressChanged(arg0: SeekBar, arg1: Int, arg2: Boolean) {
                if (manualRotSensitvityChangeInProgress) {
                    rotSensitivityChanged(rotSensitivityControl.progress)
                }
            }
        })

        effectsVolumeControl = findViewById(R.id.effectsVolumeSeekBar) as SeekBar
        effectsVolumeControl.max = 100
        effectsVolumeControl.progress = 50
        effectsVolumeControl.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStartTrackingTouch(arg0: SeekBar) {
                manualEffectsVolumeChangeInProgress = true
            }

            override fun onStopTrackingTouch(arg0: SeekBar) {
                manualEffectsVolumeChangeInProgress = false
            }

            override fun onProgressChanged(arg0: SeekBar, arg1: Int, arg2: Boolean) {
                if (manualEffectsVolumeChangeInProgress) {
                    effectsVolumeChanged(arg1 * effectsVolumeFactor / effectsVolumeControl.max)
                }
            }
        })

        musicVolumeControl = findViewById(R.id.musicVolumeSeekBar) as SeekBar
        musicVolumeControl.max = 100
        musicVolumeControl.progress = 50
        musicVolumeControl.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStartTrackingTouch(arg0: SeekBar) {
                manualMusicVolumeChangeInProgress = true
            }

            override fun onStopTrackingTouch(arg0: SeekBar) {
                manualMusicVolumeChangeInProgress = false
            }

            override fun onProgressChanged(arg0: SeekBar, arg1: Int, arg2: Boolean) {
                if (manualMusicVolumeChangeInProgress) {
                    musicVolumeChanged(arg1 * musicVolumeFactor / musicVolumeControl.max)
                }
            }
        })

        btnEnableVibration = this.findViewById(R.id.btnEnableVibration)
        btnEnableVibration.setOnCheckedChangeListener { _, isChecked ->
            enableVibrationChanged(isChecked)
        }

        btnEnableRadio = this.findViewById(R.id.btnEnableRadio)
        btnEnableRadio.setOnCheckedChangeListener { _, isChecked ->
            enableRadioChanged(isChecked)
        }

        txtCurrentCallSign = findViewById(R.id.txtCurrentCallsign)

        val changeButton = findViewById<Button>(R.id.btnChangeCallsign)
        changeButton.setOnClickListener {
            mainActivity.openEditCallSignView(this)
        }

        val aboutView = findViewById<ConstraintLayout>(R.id.aboutView).apply { visibility = GONE }
        findViewById<Button>(R.id.btnAbout).apply {
            setOnClickListener {
                aboutView.visibility = VISIBLE
            }
        }

        appVersionLabel = findViewById(R.id.appVersionLabel)

        findViewById<ImageView>(R.id.closeAboutDialog).apply {
            setOnClickListener {
                aboutView.visibility = GONE
            }
        }

        val closeButton = this.findViewById<Button>(R.id.btnCloseSettings)
        closeButton.setOnClickListener({
            mainActivity.closeSettings()
        })

        findViewById<TextView>(R.id.callSignLabel).apply { setOnClickListener { callSignLabelClicked() } }

        adminPasswordEditText = this.findViewById(R.id.adminPassword)
        adminPasswordEditText.addTextChangedListener(this)

        adminButton = this.findViewById<Button>(R.id.btnAdmin).apply {
            setOnClickListener { mainActivity.showAdminView() }
            visibility = INVISIBLE
        }
    }

    fun loadScreen(glView: GameSurfaceView?) {
        this.glView = glView

        val preferences = mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
        tranSensitivityControl.progress = preferences.getInt(mainActivity.TRAN_SENSITIVITY_SETTING, 5)
        rotSensitivityControl.progress = preferences.getInt(mainActivity.ROT_SENSITIVITY_SETTING, 5)
        tranSensitivityLabel.text = tranSensitivityControl.progress.toString()
        rotSensitivityLabel.text = rotSensitivityControl.progress.toString()

        updateEffectsVolumeSliderPosition(mainActivity.audioPlayer.currentEffectsVolume)
        updateMusicVolumeSliderPosition(mainActivity.audioPlayer.currentMusicVolume)
        updateVibrationSwitch(mainActivity.haptics.enabled)
        updateRadioSwitch(mainActivity.isRadioSettingEnabled())

        if (mainActivity.callsign != null) {
            txtCurrentCallSign.text = mainActivity.callsign!!
        }

        appVersionLabel.text = mainActivity.appVersionName

        hideAdminPassword()
        if (mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).getBoolean(mainActivity.ADMIN_MODE, false)) {
            adminButton.visibility = VISIBLE
        }
        else {
            adminButton.visibility = INVISIBLE
        }
    }

    fun tranSensitivityChanged(newValue: Int) {
        mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
            putInt(mainActivity.TRAN_SENSITIVITY_SETTING, newValue)
        }
        tranSensitivityLabel.text = newValue.toString()
        if (glView != null) {
            glView!!.updateTranSensitivity(newValue)
        }
    }

    fun rotSensitivityChanged(newValue: Int) {
        mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
            putInt(mainActivity.ROT_SENSITIVITY_SETTING, newValue)
        }
        rotSensitivityLabel.text = newValue.toString()
        mainActivity.updateRotSensitivity(newValue)
    }

    fun updateEffectsVolumeSliderPosition(position: Float) {
        effectsVolumeControl.progress = (position * effectsVolumeControl.max / effectsVolumeFactor).toInt()
    }

    fun updateMusicVolumeSliderPosition(position: Float) {
        musicVolumeControl.progress = (position * musicVolumeControl.max / musicVolumeFactor).toInt()
    }

    fun updateVibrationSwitch(isChecked: Boolean) {
        btnEnableVibration.isChecked = isChecked
    }
    fun updateRadioSwitch(isChecked: Boolean) {
        btnEnableRadio.isChecked = isChecked
    }

    fun effectsVolumeChanged(newVolume: Float) {

        mainActivity.audioPlayer.setEffectsVolume(newVolume)

        mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
            putFloat(mainActivity.EFFECTS_VOLUME_SETTING, newVolume)
        }
    }

    fun musicVolumeChanged(newVolume: Float) {

        mainActivity.audioPlayer.setMusicVolume(newVolume)
        mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
            putFloat(mainActivity.MUSIC_VOLUME_SETTING, newVolume)
        }
    }

    fun enableVibrationChanged(isChecked: Boolean) {

        mainActivity.haptics.enabled = isChecked
        mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
            putBoolean(mainActivity.VIBRATION_EFFECT, isChecked)
        }
    }
    fun enableRadioChanged(isChecked: Boolean) {

        mainActivity.audioPlayer.setRadioEnabled(isChecked)
        mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
            putBoolean(mainActivity.RADIO_ENABLED, isChecked)
        }
    }

    override fun editCallsignChanged() {
        txtCurrentCallSign.text = mainActivity.callsign!!
    }

    override fun editCallsignCancel() {}

    override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

    override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

        if (!adminPasswordEditTextChangeProgrammatic) {
            if (adminPasswordEditText.text.toString() == "sinner") {
                mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    putBoolean(mainActivity.ADMIN_MODE, true)
                    adminButton.visibility = VISIBLE
                    clearAdminPasswordEditText()
                }
            } else {
                mainActivity.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    remove(mainActivity.ADMIN_MODE)
                    adminButton.visibility = INVISIBLE
                }
            }
        }
    }

    fun hideAdminPassword() {
        adminClickCount = 0
        adminPasswordEditText.visibility = INVISIBLE
    }

    fun callSignLabelClicked() {
        adminClickCount++

        if (adminClickCount == 5) {
            adminPasswordEditText.visibility = VISIBLE
        } else {
            adminPasswordEditText.visibility = INVISIBLE
        }

        if (adminClickCount == 6) {
            adminClickCount = 0
        }
    }

    var adminPasswordEditTextChangeProgrammatic = false
    fun clearAdminPasswordEditText() {
        adminPasswordEditTextChangeProgrammatic = true
        adminPasswordEditText.setText("")
        adminPasswordEditTextChangeProgrammatic = false
    }

    override fun afterTextChanged(p0: Editable?) {}


}