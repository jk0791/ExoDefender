package com.jimjo.exodefender

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import kotlin.random.Random

data class RadioClipVariant(
    val a: RadioClip,
    val b: RadioClip
)
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
//    val laser1 = Soundfile(R.raw.laser1, 0.5f)
    val laser_m = Soundfile(R.raw.laser_m, 0.5f)
    val laser_l = Soundfile(R.raw.laser_l, 0.5f)
    val laser_h = Soundfile(R.raw.laser_h, 0.5f)
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

    var currentRadioVoiceVariant = RadioVoiceVariant.A
        private set

    // --- Radio clips ---

    private val radioCasStarted = listOf(
        RadioClipVariant(rc(R.raw.radio_cas_start_01_a, 0.90f, 2200), rc(R.raw.radio_cas_start_01_b, 0.90f, 2200)),
        RadioClipVariant(rc(R.raw.radio_cas_start_02_a, 0.90f, 2100), rc(R.raw.radio_cas_start_02_b, 0.90f, 2100)),
        RadioClipVariant(rc(R.raw.radio_cas_start_03_a, 0.90f, 2200), rc(R.raw.radio_cas_start_03_b, 0.90f, 2200)),
        RadioClipVariant(rc(R.raw.radio_cas_start_04_a, 0.90f, 2200), rc(R.raw.radio_cas_start_04_b, 0.90f, 2200)),
        RadioClipVariant(rc(R.raw.radio_cas_start_05_a, 0.90f, 2100), rc(R.raw.radio_cas_start_05_b, 0.90f, 2100)),
        RadioClipVariant(rc(R.raw.radio_cas_start_06_a, 0.90f, 2200), rc(R.raw.radio_cas_start_06_b, 0.90f, 2200)),
        RadioClipVariant(rc(R.raw.radio_cas_start_07_a, 0.90f, 2200), rc(R.raw.radio_cas_start_07_b, 0.90f, 2200)),
        RadioClipVariant(rc(R.raw.radio_cas_start_08_a, 0.90f, 2100), rc(R.raw.radio_cas_start_08_b, 0.90f, 2100)),
        RadioClipVariant(rc(R.raw.radio_cas_start_09_a, 0.90f, 2200), rc(R.raw.radio_cas_start_09_b, 0.90f, 2200)),
        RadioClipVariant(rc(R.raw.radio_cas_start_10_a, 0.90f, 2100), rc(R.raw.radio_cas_start_10_b, 0.90f, 2100)),
    )

    private val radioFriendlyLoss = listOf(
        RadioClipVariant(rc(R.raw.radio_loss_01_a, 0.95f, 2200), rc(R.raw.radio_loss_01_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_02_a, 0.95f, 2200), rc(R.raw.radio_loss_02_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_03_a, 0.95f, 2200), rc(R.raw.radio_loss_03_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_04_a, 0.95f, 2200), rc(R.raw.radio_loss_04_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_05_a, 0.95f, 2200), rc(R.raw.radio_loss_05_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_06_a, 0.95f, 2200), rc(R.raw.radio_loss_06_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_07_a, 0.95f, 2200), rc(R.raw.radio_loss_07_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_08_a, 0.95f, 2200), rc(R.raw.radio_loss_08_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_09_a, 0.95f, 2200), rc(R.raw.radio_loss_09_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_10_a, 0.95f, 2200), rc(R.raw.radio_loss_10_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_11_a, 0.95f, 2200), rc(R.raw.radio_loss_11_b, 0.95f, 2200)),
        RadioClipVariant(rc(R.raw.radio_loss_12_a, 0.95f, 2200), rc(R.raw.radio_loss_12_b, 0.95f, 2200)),
    )

    private val radioGratitude = listOf(
        RadioClipVariant(rc(R.raw.radio_thanks_01_a, 0.85f, 2200), rc(R.raw.radio_thanks_01_b, 0.85f, 2200)),
        RadioClipVariant(rc(R.raw.radio_thanks_02_a, 0.85f, 2200), rc(R.raw.radio_thanks_02_b, 0.85f, 2200)),
        RadioClipVariant(rc(R.raw.radio_thanks_03_a, 0.85f, 2200), rc(R.raw.radio_thanks_03_b, 0.85f, 2200)),
        RadioClipVariant(rc(R.raw.radio_thanks_04_a, 0.85f, 2200), rc(R.raw.radio_thanks_04_b, 0.85f, 2200)),
        RadioClipVariant(rc(R.raw.radio_thanks_05_a, 0.85f, 2200), rc(R.raw.radio_thanks_05_b, 0.85f, 2200)),
        RadioClipVariant(rc(R.raw.radio_thanks_06_a, 0.85f, 2200), rc(R.raw.radio_thanks_06_b, 0.85f, 2200)),
        RadioClipVariant(rc(R.raw.radio_thanks_07_a, 0.85f, 2200), rc(R.raw.radio_thanks_07_b, 0.85f, 2200)),
        RadioClipVariant(rc(R.raw.radio_thanks_08_a, 0.85f, 2200), rc(R.raw.radio_thanks_08_b, 0.85f, 2200)),
        RadioClipVariant(rc(R.raw.radio_thanks_09_a, 0.85f, 2200), rc(R.raw.radio_thanks_09_b, 0.85f, 2200)),
    )

    private val radioForwardProgress = listOf(
        RadioClipVariant(rc(R.raw.radio_success_01_a, 0.85f, 2100), rc(R.raw.radio_success_01_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_02_a, 0.85f, 2100), rc(R.raw.radio_success_02_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_03_a, 0.85f, 2100), rc(R.raw.radio_success_03_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_04_a, 0.85f, 2100), rc(R.raw.radio_success_04_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_05_a, 0.85f, 2100), rc(R.raw.radio_success_05_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_06_a, 0.85f, 2100), rc(R.raw.radio_success_06_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_07_a, 0.85f, 2100), rc(R.raw.radio_success_07_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_08_a, 0.85f, 2100), rc(R.raw.radio_success_08_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_09_a, 0.85f, 2100), rc(R.raw.radio_success_09_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_10_a, 0.85f, 2100), rc(R.raw.radio_success_10_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_11_a, 0.85f, 2100), rc(R.raw.radio_success_11_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_12_a, 0.85f, 2100), rc(R.raw.radio_success_12_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_13_a, 0.85f, 2100), rc(R.raw.radio_success_13_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_14_a, 0.85f, 2100), rc(R.raw.radio_success_14_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_15_a, 0.85f, 2100), rc(R.raw.radio_success_15_b, 0.85f, 2100)),
        RadioClipVariant(rc(R.raw.radio_success_16_a, 0.85f, 2100), rc(R.raw.radio_success_16_b, 0.85f, 2100)),
    )

    private val radioShipDestroyed = listOf(
        RadioClipVariant(rc(R.raw.radio_failure_01_a, 1.0f, 2300), rc(R.raw.radio_failure_01_b, 1.0f, 2300)),
        RadioClipVariant(rc(R.raw.radio_failure_02_a, 1.0f, 2300), rc(R.raw.radio_failure_02_b, 1.0f, 2300)),
        RadioClipVariant(rc(R.raw.radio_failure_03_a, 1.0f, 2300), rc(R.raw.radio_failure_03_b, 1.0f, 2300)),
    )

    private val radioStructureWarning = listOf(
        RadioClipVariant(rc(R.raw.radio_structure_warning_01_a, 1.0f, 2000), rc(R.raw.radio_structure_warning_01_b, 1.0f, 2000)),
        RadioClipVariant(rc(R.raw.radio_structure_warning_02_a, 1.0f, 2000), rc(R.raw.radio_structure_warning_02_b, 1.0f, 2000)),
        RadioClipVariant(rc(R.raw.radio_structure_warning_03_a, 1.0f, 2000), rc(R.raw.radio_structure_warning_03_b, 1.0f, 2000)),
        RadioClipVariant(rc(R.raw.radio_structure_warning_04_a, 1.0f, 2000), rc(R.raw.radio_structure_warning_04_b, 1.0f, 2000)),
    )

    private val radioDefendStarted = listOf(
        RadioClipVariant(rc(R.raw.radio_defend_start_01_a, 1.0f, 2500), rc(R.raw.radio_defend_start_01_b, 1.0f, 2500)),
        RadioClipVariant(rc(R.raw.radio_defend_start_02_a, 1.0f, 2500), rc(R.raw.radio_defend_start_02_b, 1.0f, 2500)),
        RadioClipVariant(rc(R.raw.radio_defend_start_03_a, 1.0f, 2500), rc(R.raw.radio_defend_start_03_b, 1.0f, 2500)),
    )

    private val radioEvacStarted = listOf(
        RadioClipVariant(rc(R.raw.radio_evac_start_01_a, 1.0f, 3500), rc(R.raw.radio_evac_start_01_b, 1.0f, 3500)),
        RadioClipVariant(rc(R.raw.radio_evac_start_02_a, 1.0f, 3500), rc(R.raw.radio_evac_start_02_b, 1.0f, 3500)),
        RadioClipVariant(rc(R.raw.radio_evac_start_03_a, 1.0f, 3500), rc(R.raw.radio_evac_start_03_b, 1.0f, 3500)),
    )

    private val radioEvacAll = listOf(
        RadioClipVariant(rc(R.raw.radio_evac_all_01_a, 1.0f, 2000), rc(R.raw.radio_evac_all_01_b, 1.0f, 2000)),
        RadioClipVariant(rc(R.raw.radio_evac_all_02_a, 1.0f, 2000), rc(R.raw.radio_evac_all_02_b, 1.0f, 2000)),
        RadioClipVariant(rc(R.raw.radio_evac_all_03_a, 1.0f, 2000), rc(R.raw.radio_evac_all_03_b, 1.0f, 2000)),
    )

    private val radioEvacWarning = listOf(
        RadioClipVariant(rc(R.raw.radio_evac_warning_01_a, 1.0f, 2500), rc(R.raw.radio_evac_warning_01_b, 1.0f, 2500)),
        RadioClipVariant(rc(R.raw.radio_evac_warning_02_a, 1.0f, 2500), rc(R.raw.radio_evac_warning_02_b, 1.0f, 2500)),
    )

    private val radioStructureDestroyed = listOf(
        RadioClipVariant(rc(R.raw.radio_structure_destroyed_01_a, 1.0f, 2100), rc(R.raw.radio_structure_destroyed_01_b, 1.0f, 2100)),
        RadioClipVariant(rc(R.raw.radio_structure_destroyed_02_a, 1.0f, 2100), rc(R.raw.radio_structure_destroyed_02_b, 1.0f, 2100)),
        RadioClipVariant(rc(R.raw.radio_structure_destroyed_03_a, 1.0f, 2100), rc(R.raw.radio_structure_destroyed_03_b, 1.0f, 2100)),
    )

    // --- Radio profiles ---

    private fun voiceClips(list: List<RadioClipVariant>): List<RadioClip> =
        when (currentRadioVoiceVariant) {
            RadioVoiceVariant.A -> list.map { it.a }
            RadioVoiceVariant.B -> list.map { it.b }
        }

    private fun buildProfiles(): Map<RadioCueType, RadioRequestProfile> = mapOf(
        RadioCueType.CAS_STARTED to RadioRequestProfile(
            priority = 90,
            clips = voiceClips(radioCasStarted),
            delayMs = 1800,
            repeatable = false,
            blocksOthersUntilPlayed = true,
        ),
        RadioCueType.DEFEND_STARTED to RadioRequestProfile(
            priority = 90,
            clips = voiceClips(radioDefendStarted),
            delayMs = 1800,
            repeatable = false,
            blocksOthersUntilPlayed = true,
        ),
        RadioCueType.EVAC_STARTED to RadioRequestProfile(
            priority = 90,
            clips = voiceClips(radioEvacStarted),
            delayMs = 1800,
            repeatable = false,
            blocksOthersUntilPlayed = true,
            suppressLowerPriorityAfterPlayMs = 1500,
        ),
        RadioCueType.SHIP_DESTROYED to RadioRequestProfile(
            priority = 50,
            clips = voiceClips(radioShipDestroyed),
            delayMs = 500,
            repeatable = false,
            closesRadioAfterPlay = true,
        ),
        RadioCueType.GRATITUDE to RadioRequestProfile(
            priority = 50,
            clips = voiceClips(radioGratitude),
            delayMs = 800,
            repeatable = false,
            closesRadioAfterPlay = true,
        ),
        RadioCueType.STRUCTURE_WARNING to RadioRequestProfile(
            priority = 80,
            clips = voiceClips(radioStructureWarning),
            delayMs = 150,
            repeatable = false,
        ),
        RadioCueType.STRUCTURE_DESTROYED to RadioRequestProfile(
            priority = 80,
            clips = voiceClips(radioStructureDestroyed),
            delayMs = 1300,
            repeatable = false,
            closesRadioAfterPlay = true,
        ),
        RadioCueType.EVAC_ALL to RadioRequestProfile(
            priority = 80,
            clips = voiceClips(radioEvacAll),
            delayMs = 150,
            repeatable = false,
        ),
        RadioCueType.EVAC_WARNING to RadioRequestProfile(
            priority = 50,
            clips = voiceClips(radioEvacWarning),
            delayMs = 500,
            repeatable = false,
        ),
        RadioCueType.FRIENDLY_LOSS to RadioRequestProfile(
            priority = 10,
            clips = voiceClips(radioFriendlyLoss),
            delayMs = 150,
            chance = 0.40f,
            cooldownMs = 8000,
            expiresAfterMs = 3500,
            avoidRecentCount = 2,
            delayJitterMs = 120,
            repeatable = true,
        ),
        RadioCueType.FORWARD_PROGRESS to RadioRequestProfile(
            priority = 10,
            clips = voiceClips(radioForwardProgress),
            delayMs = 150,
            chance = 0.40f,
            cooldownMs = 8000,
            expiresAfterMs = 2500,
            avoidRecentCount = 2,
            delayJitterMs = 200,
            repeatable = true,
        ),
    )

    init {
        radio = RadioManager(
            playClip = { sf -> playRadioClip(sf) },
            profilesByType = buildProfiles()
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
        laser_m.loadIntoSoundPool(context, soundPool)
        laser_l.loadIntoSoundPool(context, soundPool)
        laser_h.loadIntoSoundPool(context, soundPool)
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

    fun chooseRadioVoiceVariantForLevel() {
        currentRadioVoiceVariant =
            if (Random.nextBoolean()) RadioVoiceVariant.A else RadioVoiceVariant.B

        radio.setProfiles(buildProfiles())
    }

    private fun allRadioClipVariants(): List<RadioClipVariant> =
        buildList {
            addAll(radioCasStarted)
            addAll(radioDefendStarted)
            addAll(radioEvacStarted)
            addAll(radioFriendlyLoss)
            addAll(radioForwardProgress)
            addAll(radioStructureWarning)
            addAll(radioEvacAll)
            addAll(radioEvacWarning)
            addAll(radioStructureDestroyed)
            addAll(radioShipDestroyed)
            addAll(radioGratitude)
        }

    private fun loadAllRadioClips() {
        allRadioClipVariants()
            .flatMap { listOf(it.a, it.b) }
            .distinct()
            .forEach { it.sound.loadIntoSoundPool(context, soundPool) }
    }

    // --- SFX helpers ---

    fun playLaser(): Int = playSound(
        when (Random.nextInt(6)) {
            0 -> laser_m
            1 -> laser_m
            2 -> laser_m
            3 -> laser_m
            4 -> laser_l
            else -> laser_h
        }
    )

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

            context.resources.openRawResourceFd(musicFile.resourceId).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
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