package com.jimjo.exodefender

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
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
    val ai_landing = Soundfile(R.raw.ai_landing, 1f)

    // --- Radio clip helper ---

    private fun rc(resourceId: Int, volumeFactor: Float, durationMs: Int): RadioClip =
        RadioClip(Soundfile(resourceId, volumeFactor), durationMs)

    // --- Radio clips ---

    private val radioCheckIn = listOf(
        rc(R.raw.radio_arrival_01_a, 0.90f, 2200),
        rc(R.raw.radio_arrival_02_a, 0.90f, 2100),
        rc(R.raw.radio_arrival_03_a, 0.90f, 2200),
        rc(R.raw.radio_arrival_04_a, 0.90f, 2200),
        rc(R.raw.radio_arrival_05_a, 0.90f, 2100),
        rc(R.raw.radio_arrival_06_a, 0.90f, 2200),
        rc(R.raw.radio_arrival_07_a, 0.90f, 2200),
        rc(R.raw.radio_arrival_08_a, 0.90f, 2100),
        rc(R.raw.radio_arrival_09_a, 0.90f, 2200),
        rc(R.raw.radio_arrival_10_a, 0.90f, 2100),
    )

    private val radioFriendlyLoss = listOf(
        rc(R.raw.radio_loss_01_a, 0.95f, 2200),
        rc(R.raw.radio_loss_02_a, 0.95f, 2200),
        rc(R.raw.radio_loss_03_a, 0.95f, 2200),
        rc(R.raw.radio_loss_04_a, 0.95f, 2200),
        rc(R.raw.radio_loss_05_a, 0.95f, 2200),
        rc(R.raw.radio_loss_06_a, 0.95f, 2200),
        rc(R.raw.radio_loss_07_a, 0.95f, 2200),
        rc(R.raw.radio_loss_08_a, 0.95f, 2200),
        rc(R.raw.radio_loss_09_a, 0.95f, 2200),
        rc(R.raw.radio_loss_10_a, 0.95f, 2200),
        rc(R.raw.radio_loss_11_a, 0.95f, 2200),
        rc(R.raw.radio_loss_12_a, 0.95f, 2200),
    )

    private val radioGratitude = listOf(
        rc(R.raw.radio_thanks_01_a, 0.85f, 2200),
        rc(R.raw.radio_thanks_02_a, 0.85f, 2200),
        rc(R.raw.radio_thanks_03_a, 0.85f, 2200),
        rc(R.raw.radio_thanks_04_a, 0.85f, 2200),
        rc(R.raw.radio_thanks_05_a, 0.85f, 2200),
        rc(R.raw.radio_thanks_06_a, 0.85f, 2200),
        rc(R.raw.radio_thanks_07_a, 0.85f, 2200),
        rc(R.raw.radio_thanks_08_a, 0.85f, 2200),
        rc(R.raw.radio_thanks_09_a, 0.85f, 2200),
    )

    private val radioForwardProgress = listOf(
        rc(R.raw.radio_success_01_a, 0.85f, 2100),
        rc(R.raw.radio_success_02_a, 0.85f, 2100),
        rc(R.raw.radio_success_03_a, 0.85f, 2100),
        rc(R.raw.radio_success_04_a, 0.85f, 2100),
        rc(R.raw.radio_success_05_a, 0.85f, 2100),
        rc(R.raw.radio_success_06_a, 0.85f, 2100),
        rc(R.raw.radio_success_07_a, 0.85f, 2100),
        rc(R.raw.radio_success_08_a, 0.85f, 2100),
        rc(R.raw.radio_success_09_a, 0.85f, 2100),
        rc(R.raw.radio_success_10_a, 0.85f, 2100),
        rc(R.raw.radio_success_11_a, 0.85f, 2100),
        rc(R.raw.radio_success_12_a, 0.85f, 2100),
        rc(R.raw.radio_success_13_a, 0.85f, 2100),
        rc(R.raw.radio_success_14_a, 0.85f, 2100),
        rc(R.raw.radio_success_15_a, 0.85f, 2100),
        rc(R.raw.radio_success_16_a, 0.85f, 2100),
    )

    private val radioShipDestroyed = listOf(
        rc(R.raw.radio_failure_01_a, 1.0f, 2300),
        rc(R.raw.radio_failure_02_a, 1.0f, 2300),
        rc(R.raw.radio_failure_03_a, 1.0f, 2300),
    )

    private val radioStructureWarning = listOf(
        rc(R.raw.radio_temp_structure_warning_01, 1.0f, 2000),
        rc(R.raw.radio_temp_structure_warning_02, 1.0f, 2000),
    )

    private val radioDefendStarted = listOf(
        rc(R.raw.radio_temp_defend_start_01, 1.0f, 2200),
        rc(R.raw.radio_temp_defend_start_02, 1.0f, 2200),
    )

    private val radioEvacStarted = listOf(
        rc(R.raw.radio_temp_evac_start_01, 1.0f, 2200),
        rc(R.raw.radio_temp_evac_start_02, 1.0f, 2200),
    )

    private val radioEvacAll = listOf(
        rc(R.raw.radio_temp_evac_all_01, 1.0f, 2000),
        rc(R.raw.radio_temp_evac_all_02, 1.0f, 2000),
    )

    private val radioEvacWarning = listOf(
        rc(R.raw.radio_temp_evac_warning_01, 1.0f, 2100),
        rc(R.raw.radio_temp_evac_warning_02, 1.0f, 1900),
    )

    private val radioStructureDestroyed = listOf(
        rc(R.raw.radio_temp_structure_destroyed_01, 1.0f, 2100),
        rc(R.raw.radio_temp_structure_destroyed_02, 1.0f, 2100),
    )

    // --- Radio profiles ---

    private val profilesByType = mapOf(
        RadioCueType.CAS_STARTED to RadioRequestProfile(
            priority = 90,
            clips = radioCheckIn,
            delayMs = 1800,
            repeatable = false,
            blocksOthersUntilPlayed = true,
        ),
        RadioCueType.DEFEND_STARTED to RadioRequestProfile(
            priority = 90,
            clips = radioDefendStarted,
            delayMs = 1800,
            repeatable = false,
            blocksOthersUntilPlayed = true,
        ),
        RadioCueType.EVAC_STARTED to RadioRequestProfile(
            priority = 90,
            clips = radioEvacStarted,
            delayMs = 1800,
            repeatable = false,
            blocksOthersUntilPlayed = true,
        ),
        RadioCueType.SHIP_DESTROYED to RadioRequestProfile(
            priority = 50,
            clips = radioShipDestroyed,
            delayMs = 500,
            repeatable = false,
            closesRadioAfterPlay = true,
        ),
        RadioCueType.GRATITUDE to RadioRequestProfile(
            priority = 50,
            clips = radioGratitude,
            delayMs = 800,
            repeatable = false,
            closesRadioAfterPlay = true,
        ),
        RadioCueType.STRUCTURE_WARNING to RadioRequestProfile(
            priority = 80,
            clips = radioStructureWarning,
            delayMs = 150,
            repeatable = false,
        ),
        RadioCueType.STRUCTURE_DESTROYED to RadioRequestProfile(
            priority = 80,
            clips = radioStructureDestroyed,
            delayMs = 1300,
            repeatable = false,
            closesRadioAfterPlay = true,
        ),
        RadioCueType.EVAC_ALL to RadioRequestProfile(
            priority = 80,
            clips = radioEvacAll,
            delayMs = 150,
            repeatable = false,
        ),
        RadioCueType.EVAC_WARNING to RadioRequestProfile(
            priority = 50,
            clips = radioEvacWarning,
            delayMs = 500,
            repeatable = false,
        ),
        RadioCueType.FRIENDLY_LOSS to RadioRequestProfile(
            priority = 10,
            clips = radioFriendlyLoss,
            delayMs = 150,
            chance = 0.40f,
            cooldownMs = 8000,
            expiresAfterMs = 3500,
            avoidRecentCount = 2,
            repeatable = true,
        ),
        RadioCueType.FORWARD_PROGRESS to RadioRequestProfile(
            priority = 10,
            clips = radioForwardProgress,
            delayMs = 150,
            chance = 0.40f,
            cooldownMs = 8000,
            expiresAfterMs = 2500,
            avoidRecentCount = 2,
            repeatable = true,
        ),
    )

    init {
        radio = RadioManager(
            playClip = { sf -> playRadioClip(sf) },
            profilesByType = profilesByType
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

        // Load AI voices
        ai_intro.loadIntoSoundPool(context, soundPool)
        ai_yaw.loadIntoSoundPool(context, soundPool)
        ai_pitch.loadIntoSoundPool(context, soundPool)
        ai_slide.loadIntoSoundPool(context, soundPool)
        ai_weapons.loadIntoSoundPool(context, soundPool)
        ai_throttle.loadIntoSoundPool(context, soundPool)
        ai_controls_trg_complete.loadIntoSoundPool(context, soundPool)
        ai_landing.loadIntoSoundPool(context, soundPool)

        // Load radio
        loadAllRadioClips()
    }

    private fun loadAllRadioClips() {
        profilesByType.values
            .flatMap { it.clips }
            .forEach { it.sound.loadIntoSoundPool(context, soundPool) }
    }

    // --- SFX helpers ---

    fun playLaser(): Int = playSound(if (Random.nextBoolean()) laser1 else laser2)

    fun playExplosion(): Int =
        playSound(arrayOf(explosion1, explosion2, explosion3).random())

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

    fun setRadioEnabled(on: Boolean) {
        radio.setEnabled(on)
        if (!on) {
            resetRadio()
        }
    }

    fun resetRadio() {
        radio.clear()
        radio.log("=== RADIO RESET ===")
    }

    /** Call every frame with mission elapsed time (ms). */
    fun updateRadio(missionElapsedMs: Int) {
        radio.tick(missionElapsedMs)
    }
}