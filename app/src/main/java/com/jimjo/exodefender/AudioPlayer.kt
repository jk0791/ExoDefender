package com.jimjo.exodefender

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import kotlin.math.max
import kotlin.random.Random

class AudioPlayer(val context: Context) {

    class Soundfile(var resourceId: Int, val volumeFactor: Float) {
        var soundPoolId = 0
        fun loadIntoSoundPool(context: Context, soundPool: SoundPool) {
            soundPoolId = soundPool.load(context, resourceId, 1)
        }
    }

    internal val player = MediaPlayer()

    val radio: RadioManager
    var aiVoiceOverEnabled = true
    private lateinit var soundPool: SoundPool

    // Volumes
    var currentMusicVolume = 0.5f
    private var currentMusicFileVolumeFactor = 1f
    var currentEffectsVolume = 0.25f
    var radioVolumeMultiplier: Float = 0.40f
    var paused = false

    // Music
    var currentMusicIndex: Int? = null
    val musicFiles: Array<Soundfile> = arrayOf(
        Soundfile(R.raw.warzone, 0.4f),
        Soundfile(R.raw.raining_bullets, 0.4f),
    )

    // SFX
    val laser1 = Soundfile(R.raw.laser1, 0.5f)
    val laser2 = Soundfile(R.raw.laser2, 0.5f)
    val explosion1 = Soundfile(R.raw.explosion1, 1f)
    val explosion2 = Soundfile(R.raw.explosion2, 1f)
    val explosion3 = Soundfile(R.raw.explosion3, 1.5f)
    val explosion4 = Soundfile(R.raw.explosion4, 0.3f)

    val ai_intro = Soundfile(R.raw.ai_intro, 1f)
    val ai_yaw = Soundfile(R.raw.ai_yaw, 1f)
    val ai_pitch = Soundfile(R.raw.ai_pitch, 1f)
    val ai_slide = Soundfile(R.raw.ai_slide, 1f)
    val ai_weapons = Soundfile(R.raw.ai_weapons, 1f)
    val ai_throttle = Soundfile(R.raw.ai_throttle, 1f)
    val ai_controls_trg_complete = Soundfile(R.raw.ai_controls_training_complete, 1f)

    private data class ScheduledAction(val atMs: Long, val action: () -> Unit)
    private val actionSchedule = ArrayList<ScheduledAction>(64)
    private var actionCursor = 0
    // Radio clips (example volumes; tune to taste)
    private val radioCheckIn = listOf(
        Soundfile(R.raw.radio_arrival_01_a, 0.90f),
        Soundfile(R.raw.radio_arrival_02_a, 0.90f),
        Soundfile(R.raw.radio_arrival_03_a, 0.90f),
        Soundfile(R.raw.radio_arrival_04_a, 0.90f),
        Soundfile(R.raw.radio_arrival_05_a, 0.90f),
        Soundfile(R.raw.radio_arrival_06_a, 0.90f),
        Soundfile(R.raw.radio_arrival_07_a, 0.90f),
        Soundfile(R.raw.radio_arrival_08_a, 0.90f),
        Soundfile(R.raw.radio_arrival_09_a, 0.90f),
        Soundfile(R.raw.radio_arrival_10_a, 0.90f),
    )
    private val radioFriendlyLoss = listOf(
        Soundfile(R.raw.radio_loss_01_a, 0.95f),
        Soundfile(R.raw.radio_loss_02_a, 0.95f),
        Soundfile(R.raw.radio_loss_03_a, 0.95f),
        Soundfile(R.raw.radio_loss_04_a, 0.95f),
        Soundfile(R.raw.radio_loss_05_a, 0.95f),
        Soundfile(R.raw.radio_loss_06_a, 0.95f),
        Soundfile(R.raw.radio_loss_07_a, 0.95f),
        Soundfile(R.raw.radio_loss_08_a, 0.95f),
        Soundfile(R.raw.radio_loss_09_a, 0.95f),
        Soundfile(R.raw.radio_loss_10_a, 0.95f),
        Soundfile(R.raw.radio_loss_11_a, 0.95f),
        Soundfile(R.raw.radio_loss_12_a, 0.95f),
    )
    private val radioGratitude = listOf(
        Soundfile(R.raw.radio_thanks_01_a, 0.85f),
        Soundfile(R.raw.radio_thanks_02_a, 0.85f),
        Soundfile(R.raw.radio_thanks_03_a, 0.85f),
        Soundfile(R.raw.radio_thanks_04_a, 0.85f),
        Soundfile(R.raw.radio_thanks_05_a, 0.85f),
        Soundfile(R.raw.radio_thanks_06_a, 0.85f),
        Soundfile(R.raw.radio_thanks_07_a, 0.85f),
        Soundfile(R.raw.radio_thanks_08_a, 0.85f),
        Soundfile(R.raw.radio_thanks_09_a, 0.85f),
    )

    private val radioForwardProgress = listOf(
        Soundfile(R.raw.radio_success_01_a, 0.85f),
        Soundfile(R.raw.radio_success_02_a, 0.85f),
        Soundfile(R.raw.radio_success_03_a, 0.85f),
        Soundfile(R.raw.radio_success_04_a, 0.85f),
        Soundfile(R.raw.radio_success_05_a, 0.85f),
        Soundfile(R.raw.radio_success_06_a, 0.85f),
        Soundfile(R.raw.radio_success_07_a, 0.85f),
        Soundfile(R.raw.radio_success_08_a, 0.85f),
        Soundfile(R.raw.radio_success_09_a, 0.85f),
        Soundfile(R.raw.radio_success_10_a, 0.85f),
        Soundfile(R.raw.radio_success_11_a, 0.85f),
        Soundfile(R.raw.radio_success_12_a, 0.85f),
        Soundfile(R.raw.radio_success_13_a, 0.85f),
        Soundfile(R.raw.radio_success_14_a, 0.85f),
        Soundfile(R.raw.radio_success_15_a, 0.85f),
        Soundfile(R.raw.radio_success_16_a, 0.85f),
    )
    private val radioShipDestroyed = listOf(
        Soundfile(R.raw.radio_failure_01_a, 1.0f),
        Soundfile(R.raw.radio_failure_02_a, 1.0f),
        Soundfile(R.raw.radio_failure_03_a, 1.0f),
    )



    init {

        radio = RadioManager(
            playClip = { sf -> playRadioClip(sf) },
            scheduleOnce = { atMs, action -> scheduleAction(atMs, action) }
        )

        player.isLooping = true
    }

    fun initialize() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(6) // radio + explosions + lasers
            .setAudioAttributes(audioAttributes)
            .build()

        // Load SFX
        laser1.loadIntoSoundPool(context, soundPool)
        laser2.loadIntoSoundPool(context, soundPool)
        explosion1.loadIntoSoundPool(context, soundPool)
        explosion2.loadIntoSoundPool(context, soundPool)
        explosion3.loadIntoSoundPool(context, soundPool)
        explosion4.loadIntoSoundPool(context, soundPool)

        // load AI voices
        ai_intro.loadIntoSoundPool(context, soundPool)
        ai_yaw.loadIntoSoundPool(context, soundPool)
        ai_pitch.loadIntoSoundPool(context, soundPool)
        ai_slide.loadIntoSoundPool(context, soundPool)
        ai_weapons.loadIntoSoundPool(context, soundPool)
        ai_throttle.loadIntoSoundPool(context, soundPool)
        ai_controls_trg_complete.loadIntoSoundPool(context, soundPool)

        // Load radio
        loadRadioList(radioCheckIn)
        loadRadioList(radioFriendlyLoss)
        loadRadioList(radioForwardProgress)
        loadRadioList(radioGratitude)
        loadRadioList(radioShipDestroyed)

        // Register radio with manager
        radio.addAll(RadioType.CHECK_IN, radioCheckIn)
        radio.addAll(RadioType.FRIENDLY_LOSS, radioFriendlyLoss)
        radio.addAll(RadioType.FORWARD_PROGRESS, radioForwardProgress)
        radio.addAll(RadioType.GRATITUDE, radioGratitude)
        radio.addAll(RadioType.SHIP_DESTROYED, radioShipDestroyed)

    }

    private fun loadRadioList(list: List<Soundfile>) {
        for (sf in list) {
            sf.loadIntoSoundPool(context, soundPool)
        }
    }

    // --- SFX helpers ---

    fun playLaser(): Int = playSound(if (Random.nextBoolean()) laser1 else laser2)
    fun playExplosion(): Int = playSound(arrayOf(explosion1, explosion2, explosion3).random())

    fun playAIVoiceOver(soundfile: Soundfile): Int {
        if (aiVoiceOverEnabled) {
            return playSound(soundfile, false)
        }
        return -1
    }

    fun playSound(soundfile: Soundfile, loop: Boolean = false): Int {
        val vol = currentEffectsVolume * soundfile.volumeFactor
        return soundPool.play(soundfile.soundPoolId, vol, vol, 0, if (loop) -1 else 0, 1f)
    }

    // Radio uses effects volume but with extra knob
    private fun playRadioClip(soundfile: Soundfile): Boolean {
        val vol = currentEffectsVolume * soundfile.volumeFactor * radioVolumeMultiplier
        val streamId = soundPool.play(soundfile.soundPoolId, vol, vol, 1, 0, 1f)
        return streamId != 0
    }


    fun setEffectsVolume(newVolume: Float) {
        currentEffectsVolume = newVolume
    }

    // --- Music ---

    fun setMusicVolume(newVolume: Float) {
        currentMusicVolume = newVolume
        updateMusicVolume()
    }

    private fun updateMusicVolume() {
        val v = currentMusicVolume * currentMusicFileVolumeFactor
        player.setVolume(v, v)
    }

    fun startMusic(musicFilesIndex: Int? = null) {
        var songChange = false

        if (musicFilesIndex != null && currentMusicIndex != musicFilesIndex) {
            currentMusicIndex = musicFilesIndex
            songChange = true
        }

        val idx = currentMusicIndex ?: return

        if (songChange) {
            player.reset()
            val musicFile = musicFiles[idx]
            currentMusicFileVolumeFactor = musicFile.volumeFactor
            updateMusicVolume()

            val afd = context.resources.openRawResourceFd(musicFile.resourceId)
            player.setDataSource(afd)
            player.prepare()
            player.start()
            player.isLooping = true
        } else if (!player.isPlaying) {
            player.start()
        }
        paused = false
    }

    fun stopSound(soundStreamId: Int) {
        soundPool.stop(soundStreamId)
        paused = false
    }

    fun stopAllSounds() {
        soundPool.autoPause()
        paused = false
    }

    fun stopMusic() {
        if (player.isPlaying) player.stop()
    }

    fun pause() {
        player.pause()
        paused = true
    }

    // --- Scheduling support (mission elapsed time) ---


    fun setRadioEnabled(on: Boolean) {
        radio.setEnabled(on)
        if (!on) {
            resetRadioSchedule() // clears scheduled one-shots too
        }
    }
    fun resetRadioSchedule() {
        actionSchedule.clear()
        actionCursor = 0
        radio.clear()
    }

    private fun scheduleAction(atMs: Long, action: () -> Unit) {

        actionSchedule.add(ScheduledAction(atMs = max(0L, atMs), action = action))
        actionSchedule.sortBy { it.atMs }
        actionCursor = actionCursor.coerceAtMost(actionSchedule.size)
    }

    /** Call every frame with mission elapsed time (ms). */
    fun updateRadio(missionElapsedMs: Long) {

        radio.tick(missionElapsedMs)

        // pump schedule
        while (actionCursor < actionSchedule.size && missionElapsedMs >= actionSchedule[actionCursor].atMs) {
            val item = actionSchedule[actionCursor]
            radio.log("EXEC at t=$missionElapsedMs firing item at=${item.atMs} cursor=$actionCursor")
            actionCursor++
            item.action.invoke()
        }
    }
}
