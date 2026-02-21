package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

class LevelsView(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    class LevelLabel(parent: LevelsView, val indexLabel: TextView, val nameLabel: TextView) {
        var level: Level? = null

        init {
            nameLabel.setOnClickListener( {
                parent.levelLabelClicked(level)
            })
        }
    }

    val mainActivity = context as MainActivity

    var levelType = Level.LevelType.MISSION

    val headingLabel: TextView
    lateinit var levelManager: LevelManager
    val levelLabels: List<LevelLabel>

    val nextCampaign: ImageView
    val prevCampaign: ImageView
    var currentCampaign: Campaign? = null //  0 for MILKRUNS, zero and higher for MISSIONS

    val defaultTextColor = Color(90f / 255, 220f / 255, 0f).toArgb() // #5ADC00
    val disabledTextColor = 0x445ADC00


    init {
        inflate(context, R.layout.levels_view, this)

        levelLabels = listOf(
            LevelLabel(this, findViewById(R.id.lvlIdx0), findViewById(R.id.lvlName0)),
            LevelLabel(this, findViewById(R.id.lvlIdx1), findViewById(R.id.lvlName1)),
            LevelLabel(this, findViewById(R.id.lvlIdx2), findViewById(R.id.lvlName2)),
            LevelLabel(this, findViewById(R.id.lvlIdx3), findViewById(R.id.lvlName3)),
            LevelLabel(this, findViewById(R.id.lvlIdx4), findViewById(R.id.lvlName4)),
            LevelLabel(this, findViewById(R.id.lvlIdx5), findViewById(R.id.lvlName5)),
            LevelLabel(this, findViewById(R.id.lvlIdx6), findViewById(R.id.lvlName6)),
            LevelLabel(this, findViewById(R.id.lvlIdx7), findViewById(R.id.lvlName7)),
            LevelLabel(this, findViewById(R.id.lvlIdx8), findViewById(R.id.lvlName8)),
            LevelLabel(this, findViewById(R.id.lvlIdx9), findViewById(R.id.lvlName9)),
        )

        if (levelLabels.size != levelsPerCampaign) {
            throw IllegalStateException("Number of level labels is different to levels per campaign")
        }

        val homeButton: ImageView = findViewById(R.id.btnHome)
        homeButton.setOnClickListener({
            mainActivity.showHomeView()
        })

        headingLabel = findViewById(R.id.levelsHeadingLabel)

        val lastFlightButton: ImageView = findViewById(R.id.btnLastFlight)
        lastFlightButton.setOnClickListener({
            mainActivity.replayLastFlight()
        })

        nextCampaign = findViewById(R.id.nextCampaign)
        nextCampaign.isEnabled = false
        nextCampaign.setOnClickListener { nextCampaignClicked() }

        prevCampaign = findViewById(R.id.prevCampaign)
        prevCampaign.isEnabled = true
        prevCampaign.setOnClickListener { prevCampaignClicked() }
    }

    fun initialize(levelManager: LevelManager) {
        this.levelManager = levelManager
    }

    fun loadLevels(levelType: Level.LevelType, gotoMissionLevelId: Int? = null, gotoCampaignCode: String? = null) {

        this.levelType = levelType


        val levelList: List<Level>

        if (levelType == Level.LevelType.MILKRUN) {
            headingLabel.text = "Low-Risk Ops"
            levelList = levelManager.milkruns
            nextCampaign.isEnabled = true
            prevCampaign.visibility = INVISIBLE
            currentCampaign = null
        }
        else { // levelType == Level.LevelType.MISSION

            // get highest unlocked mission and corresponding campaign if a campaign is not currently selected

            if (gotoMissionLevelId != null) {
                currentCampaign =
                    levelManager
                        .levelIdLookup[gotoMissionLevelId]
                        ?.let { levelManager.getCampaignFromLevel(it) }
            }
            else if (gotoCampaignCode != null) {
                // campaign index provided (and no level id) so try to open it directly
                currentCampaign = levelManager.campaignByCode[gotoCampaignCode]

            }
            else if (currentCampaign == null) {
                currentCampaign =
                    levelManager
                        .getHighestUnlockedLevel(Level.LevelType.MISSION)
                        ?.let { levelManager.getCampaignFromLevel(it) }
            }
            else {
                currentCampaign = levelManager.campaigns.firstOrNull()
            }


            if (currentCampaign != null) {

                headingLabel.text = currentCampaign!!.code + ": " + currentCampaign!!.name
                levelList = currentCampaign!!.levels

                if (levelManager.getNextNavigableCampaign(currentCampaign) != null) {
                    nextCampaign.isEnabled = true
                }
                else {
                    nextCampaign.isEnabled = false
                }
            }
            else {
                levelList = listOf()
                nextCampaign.isEnabled = false
            }

            prevCampaign.visibility = VISIBLE
            prevCampaign.isEnabled = true
        }

        for ((labelIndex, levelLabel) in levelLabels.withIndex()) {

            if (labelIndex < levelList.size) {
                val level = levelList[labelIndex]

                levelLabel.level = level
                levelLabel.indexLabel.text = (level.index + 1).toString()
                levelLabel.nameLabel.text = level.name
                levelLabel.indexLabel.visibility = VISIBLE
                levelLabel.nameLabel.visibility = VISIBLE
                if (mainActivity.levelManager.checkIfLevelUnlocked(level.id)) {
                    levelLabel.indexLabel.setTextColor(defaultTextColor)
                    levelLabel.nameLabel.setTextColor(defaultTextColor)
                    levelLabel.nameLabel.isEnabled = true
                } else {
                    levelLabel.indexLabel.setTextColor(disabledTextColor)
                    levelLabel.nameLabel.setTextColor(disabledTextColor)
                    levelLabel.nameLabel.isEnabled = false
                }
            } else {
                levelLabel.level = null
                levelLabel.indexLabel.visibility = INVISIBLE
                levelLabel.nameLabel.visibility = INVISIBLE
            }
        }
    }


    fun clearLevelLabels() {
        for (levelLabel in levelLabels) {
            levelLabel.level = null
            levelLabel.indexLabel.visibility = INVISIBLE
            levelLabel.nameLabel.visibility = INVISIBLE
        }
    }

    fun levelLabelClicked(level: Level?) {
        if (level != null) {
            mainActivity.openLevelFromLevelsView(level)
        }
        else {
            mainActivity.adminLogView.printout("level is null")
        }
    }

    fun nextCampaignClicked() {
        if (levelType == Level.LevelType.MILKRUN) {
            val first = levelManager.getNextNavigableCampaign(null)
            if (first != null) {
                loadLevels(Level.LevelType.MISSION, null, first.code)
                mainActivity.setCurrentFeature(Feature.MISSIONS)
            }
            // else: nowhere to go yet (maybe toast)
            return
        }

        // currently in missions
        val next = levelManager.getNextNavigableCampaign(currentCampaign)
        if (next != null) {
            loadLevels(Level.LevelType.MISSION, null, next.code)
            mainActivity.setCurrentFeature(Feature.MISSIONS)
        }
    }
    fun prevCampaignClicked() {
        if (levelType == Level.LevelType.MILKRUN) return

        // currently in missions
        val prev = levelManager.getPrevNavigableCampaign(currentCampaign)
        if (prev != null) {
            loadLevels(Level.LevelType.MISSION, null, prev.code)
            mainActivity.setCurrentFeature(Feature.MISSIONS)
            return
        }

        // No previous navigable campaign -> go back to milkruns
        loadLevels(Level.LevelType.MILKRUN)
        mainActivity.setCurrentFeature(Feature.MILKRUNS)
    }

}