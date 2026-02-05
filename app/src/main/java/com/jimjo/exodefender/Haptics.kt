package com.jimjo.exodefender

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

class Haptics(private val context: Context) {

    var enabled = true
    val hitDuration = 55L
    val crashDuration = 120L
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(Vibrator::class.java)
        else
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?

    private fun canVibrate(): Boolean =
        vibrator?.hasVibrator() == true

    private val hit = if (Build.VERSION.SDK_INT >= 26)
        VibrationEffect.createOneShot(hitDuration, 50)
    else null

    private val crash = if (Build.VERSION.SDK_INT >= 26)
        VibrationEffect.createOneShot(crashDuration, 180)
    else null

    fun hit() {
        if (enabled) {
            val v = vibrator ?: return
            if (!canVibrate()) return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(hit)
            else @Suppress("DEPRECATION") v.vibrate(hitDuration)
        }
    }

    fun crash() {
        if (enabled) {
            val v = vibrator ?: return
            if (!canVibrate()) return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(crash)
            else @Suppress("DEPRECATION") v.vibrate(crashDuration)
        }
    }
}