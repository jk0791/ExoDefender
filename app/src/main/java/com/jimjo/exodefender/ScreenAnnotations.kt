package com.jimjo.exodefender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import kotlin.math.min


class ScreenAnnotations(context: Context, attrs: AttributeSet? = null) :
    ConstraintLayout(context, attrs) {

    enum class TrainingType { BASIC_CONTROLS, LANDING }
    enum class StepDirection { PREV, NEXT }
    enum class ScreenLabelHorzAlignment { ALIGNED_LEFT_WITH_LABEL, ALIGNED_RIGHT_WITH_LABEL, LEFT_OF_LABEL, RIGHT_OF_LABEL }
    private val mainActivity = context as MainActivity


    private val constraintLayout: ConstraintLayout

    // screen label
    private val screenLabel: LinearLayout
    private val screenLabelText: TextView
    private val screenLabelPrevButton: ImageView
    private val screenLabelNextButton: ImageView


    // tilt
    private val imageTilt: ImageView
    private val tiltRollAnimation: AnnotationAnimation
    private val tiltPitchAnimation: AnnotationAnimation

    // slide
    private val imageSlide: ImageView
    private val slideAnimation: AnnotationAnimation

    // fire
    private val imageFire: ImageView
    private val fireAnimation: AnnotationAnimation
    private val imageThrottle: ImageView
    private val throttleAnimation: AnnotationAnimation

    // state
    var currentLevelIndex: Int? = null
    var trainingType = TrainingType.BASIC_CONTROLS
    private var stepIndex = 0
    private var running = false
    private var lastTimeMs = 0L
    private var startMs = 0L
    private var elapsedMs = 0
    private val minAnimationInterval = 120 // 33

    private var introVoicePlayed = false
    private var showPrevApplicable = true
    private var showNextApplicable = true



    init {
        inflate(context, R.layout.screen_annotations, this)
        this.setWillNotDraw(false)

        // label and nav buttons
        constraintLayout = findViewById(R.id.constraintLayout)
        screenLabel = findViewById(R.id.screenLabel)
        screenLabelText = findViewById(R.id.screenLabelText)

        screenLabelPrevButton = findViewById(R.id.screenLabelPrevButton)
        screenLabelPrevButton.setOnClickListener { changeStep(StepDirection.PREV)}

        screenLabelNextButton = findViewById(R.id.screenLabelNextButton)
        screenLabelNextButton.setOnClickListener { changeStep(StepDirection.NEXT) }

        // tilt
        imageTilt = findViewById(R.id.imageTilt)
        tiltRollAnimation = AnnotationAnimation("tut_tilt_roll", 13, imageTilt, startOscillationDirection = -1, startIndex = 5)
        tiltPitchAnimation = AnnotationAnimation("tut_tilt_pitch", 13, imageTilt)

        // slide
        imageSlide = findViewById(R.id.imageSlide)
        slideAnimation = AnnotationAnimation("tut_slide", 4, imageSlide, framesPerFrame = 3)

        // fire
        imageFire = findViewById(R.id.imageFire)
        fireAnimation = AnnotationAnimation("tut_fire", 4, imageFire)

        // throttle
        imageThrottle = findViewById(R.id.imageThrottle)
        throttleAnimation = AnnotationAnimation("tut_throttle", 2, imageThrottle, framesPerFrame = 3)

        hideEverything(true)
    }

    fun start() {
        if (currentLevelIndex == 0) startBasicControlsTutorial()
        else if (currentLevelIndex == 2) startLandingTutorial()
    }

    fun startBasicControlsTutorial() {

        trainingType = TrainingType.BASIC_CONTROLS

        tiltRollAnimation.loadBitmaps(context)
        tiltPitchAnimation.loadBitmaps(context)
        slideAnimation.loadBitmaps(context)
        fireAnimation.loadBitmaps(context)
        throttleAnimation.loadBitmaps(context)

        hideEverything(true)
        stepIndex = 0
        introVoicePlayed = false

        running = true
        lastTimeMs = System.currentTimeMillis()
        startMs = System.currentTimeMillis()
        elapsedMs = 0

        postInvalidate()
    }

    fun startLandingTutorial() {
        // TODO

        trainingType = TrainingType.LANDING

        setNavButtons(false, false)
        hideEverything()
        moveLabelTo(imageFire, ScreenLabelHorzAlignment.ALIGNED_RIGHT_WITH_LABEL, true)
        screenLabelText.text = "Reduce power to land"
        screenLabelText.visibility = VISIBLE


    }

    fun setNavButtons(showPrev: Boolean, showNext: Boolean) {
        showPrevApplicable = showPrev
        showNextApplicable = showNext
    }

    fun showButtons(on: Boolean, delay: Long) {
        if (on && showPrevApplicable) {
            screenLabelPrevButton.postDelayed({
                screenLabelPrevButton.visibility = VISIBLE
                screenLabelPrevButton.alpha = 0f
                screenLabelPrevButton.animate()
                    .alpha(1f)
                    .setDuration(400L)
                    .start()
            }, delay)
        }
        else {
            screenLabelPrevButton.visibility = INVISIBLE
            screenLabelPrevButton.alpha = 0f
        }

        if (on && showNextApplicable) {
            screenLabelNextButton.postDelayed({
                screenLabelNextButton.visibility = VISIBLE
                screenLabelNextButton.alpha = 0f
                screenLabelNextButton.animate()
                    .alpha(1f)
                    .setDuration(400L)
                    .start()
            }, delay)
        }
        else {
            screenLabelNextButton.visibility = INVISIBLE
            screenLabelPrevButton.alpha = 0f
        }
    }

    fun changeStep(direction: StepDirection) {

        if (direction == StepDirection.NEXT) {
            stepIndex++
        }
        else { // direction == StepDirection.PREV
            if (stepIndex > 0) {
                stepIndex--
            }
            else {
                return
            }
        }

        when (stepIndex) {
            1 -> {
                mainActivity.audioPlayer.playAIVoiceOver(mainActivity.audioPlayer.ai_yaw)
                setNavButtons(false, true)
                hideEverything()
                imageTilt.visibility = VISIBLE
                moveLabelTo(imageTilt, ScreenLabelHorzAlignment.LEFT_OF_LABEL, false)
                screenLabelText.text = "Tilt your device to turn left and right"
                screenLabelText.visibility = VISIBLE

                showButtons(true, 3000L)
            }
            2 -> {
                mainActivity.audioPlayer.playAIVoiceOver(mainActivity.audioPlayer.ai_pitch)
                setNavButtons(true, true)
                hideEverything()
                imageTilt.visibility = VISIBLE
                moveLabelTo(imageTilt, ScreenLabelHorzAlignment.RIGHT_OF_LABEL, false)
                screenLabelText.text = "Tilt this way to pitch up and down"
                screenLabelText.visibility = VISIBLE

                showButtons(true, 3000L)
            }
            3 -> {
                mainActivity.audioPlayer.playAIVoiceOver(mainActivity.audioPlayer.ai_slide)
                setNavButtons(true, true)
                hideEverything()
                imageSlide.visibility = VISIBLE
                moveLabelTo(imageSlide, ScreenLabelHorzAlignment.RIGHT_OF_LABEL, true)
                screenLabelText.text = "Drag here to slide/strafe"
                screenLabelText.visibility = VISIBLE

                showButtons(true, 4000L)
            }
            4 -> {
                mainActivity.audioPlayer.playAIVoiceOver(mainActivity.audioPlayer.ai_weapons)
                setNavButtons(true, true)
                hideEverything()
                imageFire.visibility = VISIBLE
                moveLabelTo(imageFire, ScreenLabelHorzAlignment.LEFT_OF_LABEL, true)
                screenLabelText.text = "Press here to fire the weapons"
                screenLabelText.visibility = VISIBLE

                showButtons(true, 3000L)
            }
            5 -> {
                mainActivity.audioPlayer.playAIVoiceOver(mainActivity.audioPlayer.ai_throttle)
                setNavButtons(true, true)
                hideEverything()
                imageThrottle.visibility = VISIBLE
                moveLabelTo(imageThrottle, ScreenLabelHorzAlignment.ALIGNED_RIGHT_WITH_LABEL, true)
                screenLabelText.text = "Drag here to speed up or down"
                screenLabelText.visibility = VISIBLE

                showButtons(true, 3000L)
            }
            6 -> {
                hideEverything()
                stepIndex = 0
                running = false
                mainActivity.showTrainingOutcomeView()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (running) {
            val timeMs = System.currentTimeMillis()
            if (timeMs - lastTimeMs > minAnimationInterval) {
                lastTimeMs = timeMs
                elapsedMs = (timeMs - startMs).toInt()

                if (trainingType == TrainingType.BASIC_CONTROLS) {

                    // initial delay before intro AI VO
                    if (!introVoicePlayed && elapsedMs > 1000) {
                        mainActivity.audioPlayer.playAIVoiceOver(mainActivity.audioPlayer.ai_intro)
                        introVoicePlayed = true
                    }

                    // initial delay before first step
                    if (stepIndex == 0 && elapsedMs > 4200) changeStep(StepDirection.NEXT)

                    // animations
                    when (stepIndex) {
                        1 -> tiltRollAnimation.playOsciallating()
                        2 -> tiltPitchAnimation.playOsciallating()
                        3 -> slideAnimation.playCycled()
                        4 -> fireAnimation.playCycled()
                        5 -> throttleAnimation.playCycled()
                        else -> hideEverything()
                    }
                }
                else if (trainingType == TrainingType.LANDING) {

                    // TODO

                }
            }

            postInvalidate()
        }
    }

    fun moveLabelTo(target: View, horzAlignment: ScreenLabelHorzAlignment, above: Boolean) {


        // create new constraintSet
        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)

        // clear existing constraints
        constraintSet.clear(screenLabel.id, ConstraintSet.LEFT)
        constraintSet.clear(screenLabel.id, ConstraintSet.RIGHT)
        constraintSet.clear(screenLabel.id, ConstraintSet.TOP)
        constraintSet.clear(screenLabel.id, ConstraintSet.BOTTOM)

        if (horzAlignment == ScreenLabelHorzAlignment.LEFT_OF_LABEL) {
            constraintSet.connect(screenLabel.id, ConstraintSet.RIGHT, target.id, ConstraintSet.LEFT)
        }
        else if (horzAlignment == ScreenLabelHorzAlignment.RIGHT_OF_LABEL) {
            constraintSet.connect(screenLabel.id, ConstraintSet.LEFT, target.id, ConstraintSet.RIGHT)
        }
        else if (horzAlignment == ScreenLabelHorzAlignment.ALIGNED_LEFT_WITH_LABEL) {
            constraintSet.connect(screenLabel.id, ConstraintSet.LEFT, target.id, ConstraintSet.LEFT)
        }
        else { // horzAlignment == ScreenLabelHorzAlignment.ALIGNED_RIGHT_WITH_LABEL
            constraintSet.connect(screenLabel.id, ConstraintSet.RIGHT, target.id, ConstraintSet.RIGHT)
        }

        if (above) {
            constraintSet.connect(screenLabel.id, ConstraintSet.BOTTOM, target.id, ConstraintSet.TOP)
        }
        else {
            constraintSet.connect(screenLabel.id, ConstraintSet.TOP, target.id, ConstraintSet.TOP)
        }

        constraintSet.applyTo(constraintLayout)
    }

    fun hideEverything(force: Boolean = false) {

        imageTilt.visibility = INVISIBLE
        imageSlide.visibility = INVISIBLE
        imageFire.visibility = INVISIBLE
        imageThrottle.visibility = INVISIBLE

        screenLabelText.visibility = INVISIBLE
        screenLabelPrevButton.visibility = INVISIBLE
        screenLabelNextButton.visibility = INVISIBLE

    }

}

class AnnotationAnimation(
    val prefix: String,
    bitmapCount: Int,
    val imageView: ImageView,
    val framesPerFrame: Int = 1,  // how many onDraw() frames for each animation frame
    val startIndex: Int = 0,
    val startOscillationDirection: Int = 1,
    val endPauseFrames: Int = 0  // how many onDraw() frames to pause before restarting a cycle
) {

    private val bitmaps: Array<Bitmap?> = Array(bitmapCount) { _ -> null }
    private var cycledAnimIndex = startIndex
    private var oscillatingAnimIndex = startIndex
    private var oscillatingAnimDirection = startOscillationDirection
    private var framesPerFrameCount = 0

    fun reset() {
        cycledAnimIndex = startIndex
        oscillatingAnimIndex = startIndex
        oscillatingAnimDirection = startOscillationDirection
        framesPerFrameCount = 0
    }

    fun playCycled() {
        framesPerFrameCount++
        if (framesPerFrameCount >= framesPerFrame) {
            cycledAnimIndex++
            framesPerFrameCount = 0
        }

        // restart if at end
        if (cycledAnimIndex > bitmaps.lastIndex + endPauseFrames) {
            cycledAnimIndex = 0
        }

        imageView.setImageBitmap(bitmaps[min(cycledAnimIndex, bitmaps.lastIndex)])
    }

    fun playOsciallating() {

        framesPerFrameCount++
        if (framesPerFrameCount >= framesPerFrame) {
            oscillatingAnimIndex += oscillatingAnimDirection
            framesPerFrameCount = 0
        }

        // reverse if at end or beginning
        if (oscillatingAnimIndex > bitmaps.lastIndex) {
            oscillatingAnimIndex = bitmaps.lastIndex
            oscillatingAnimDirection = -1
        } else if (oscillatingAnimIndex < 0) {
            oscillatingAnimIndex = 0
            oscillatingAnimDirection = +1
        }
        imageView.setImageBitmap(bitmaps[min(oscillatingAnimIndex, bitmaps.lastIndex)])
    }

    fun loadBitmaps(context: Context) {
        val missingBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.sidebar)

        for (x in 0..< bitmaps.size) {
            val resourceName = prefix + "_" + "%02d".format(x)
            val id = context.resources.getIdentifier(resourceName, "drawable", context.packageName);
            val rawBitmap = BitmapFactory.decodeResource(context.resources, id)
            if (rawBitmap != null) {
                bitmaps[x] = rawBitmap
            } else {
                bitmaps[x] = missingBitmap
                println("ERROR: $resourceName not found, using placeholder bitmap instead")
            }
        }
    }

}