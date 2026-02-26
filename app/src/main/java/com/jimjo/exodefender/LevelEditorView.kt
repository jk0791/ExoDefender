package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.setMargins

class LevelEditorView(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    val parent = context as MainActivity
    val levelsTable: TableLayout
    lateinit var levelManager: LevelManager

    var levelBuilderEnabled = false

    init {
        inflate(context, R.layout.level_editor, this)
        levelsTable = findViewById(R.id.levelsTable)


        val crateLevelButton = findViewById<Button>(R.id.createLevelButton)
        crateLevelButton.setOnClickListener { createNewLevel() }

//        val settingsButton: ImageView = findViewById(R.id.settingsButton)
//        settingsButton.setOnClickListener({
//            parent.showSettings()
//        })

        val closeButton = this.findViewById<ImageView>(R.id.closeButton)
        closeButton.setOnClickListener({
            parent.closeLevelEditorView()
        })
    }

    fun initialize(levelManager: LevelManager, levelBuilderEnabled: Boolean) {
        this.levelManager = levelManager
        this.levelBuilderEnabled = levelBuilderEnabled
    }

    fun createNewLevel() {
        parent.showLevelBuilderMetadata(null)
    }
    fun loadLevels() {
        levelsTable.removeAllViews()
        val textSize = 25f
        addHeadingsToTable(textSize)
        for (level in levelManager.development) {
            addRowToTable(level, textSize)
        }
        for (level in levelManager.training) {
            addRowToTable(level, textSize)
        }
        for (level in levelManager.milkruns) {
            addRowToTable(level, textSize)
        }
        for (level in levelManager.missionsAnyState) {
            addRowToTable(level, textSize)
        }
    }

    fun addHeadingsToTable(textSize: Float) {
        addRowToTable(null, textSize, true)
    }

    fun addRowToTable(level: Level?, textSize: Float, headingRow: Boolean = false) {
        val tableRow = TableRow(context)
        val tableRowLayoutParams = TableLayout.LayoutParams(TableLayout.LayoutParams.WRAP_CONTENT, TableLayout.LayoutParams.WRAP_CONTENT)
        tableRowLayoutParams.setMargins(8)
        tableRow.layoutParams = tableRowLayoutParams

        val campaignCodeTextView = TextView(context, null, 0, R.style.DefaultText)
        campaignCodeTextView.textSize = textSize
        if (!headingRow && level != null) {
            campaignCodeTextView.text = level.campaignCode
        }
        else {
            campaignCodeTextView.text = "CC"
        }
        val campaignCodeTextViewParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        campaignCodeTextViewParams.gravity = Gravity.CENTER
        campaignCodeTextViewParams.setMargins(0, 0, 10, 0)
        campaignCodeTextView.layoutParams = campaignCodeTextViewParams
        tableRow.addView(campaignCodeTextView)


        val indexTextView = TextView(context, null, 0, R.style.DefaultText)
        indexTextView.textSize = textSize
        if (!headingRow && level != null) {
            indexTextView.text = "${level.index + 1}"
        }
        else {
            indexTextView.text = "#"
        }
        val idTextViewParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        idTextViewParams.gravity = Gravity.CENTER
        idTextViewParams.setMargins(0, 0, 10, 0)
        indexTextView.layoutParams = idTextViewParams
        tableRow.addView(indexTextView)


        val idTextView = TextView(context, null, 0, R.style.DefaultText)
        idTextView.textSize = textSize
        if (!headingRow && level != null) {
            idTextView.text = "${level.id}"
        }
        else {
            idTextView.text = "ID"
        }
        val indexTextViewParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        indexTextViewParams.gravity = Gravity.CENTER
        indexTextViewParams.setMargins(0, 0, 10, 0)
        idTextView.layoutParams = indexTextViewParams
        tableRow.addView(idTextView)


        val descriptionTextView = TextView(context)
        descriptionTextView.setTextColor(Color.White.toArgb())
        if (!headingRow && level != null) {
            descriptionTextView.id = level.id
            descriptionTextView.setOnClickListener(object : OnClickListener {
                override fun onClick(view: View?) {
                    parent.openLevelById(view!!.id, false)
                }
            })
            descriptionTextView.text = level.name

            if (level.globalIndex == -1) {
                descriptionTextView.setTextColor(Color.Red.toArgb())
            }

        }
        else {
            descriptionTextView.text = "Name"
        }
        descriptionTextView.textSize = textSize
        val levelDescriptionParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        levelDescriptionParams.setMargins(0, 0, 10, 0)
        levelDescriptionParams.gravity = Gravity.CENTER_VERTICAL
        descriptionTextView.layoutParams = levelDescriptionParams
        tableRow.addView(descriptionTextView)


        val typeTextView = TextView(context, null, 0, R.style.DefaultText)
        typeTextView.textSize = textSize
        if (!headingRow && level != null) {
            typeTextView.text = "${level.type}"
        }
        else {
            typeTextView.text = "Type"
        }
        val typeViewParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        typeViewParams.gravity = Gravity.CENTER
        typeViewParams.setMargins(0, 0, 10, 0)
        typeTextView.layoutParams = typeViewParams
        tableRow.addView(typeTextView)

        val orderTextView = TextView(context, null, 0, R.style.DefaultText)
        orderTextView.textSize = textSize
        if (!headingRow && level != null) {
            orderTextView.text = "${level.order}"
        }
        else {
            orderTextView.text = "Ord"
        }
        val orderTextViewParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        orderTextViewParams.gravity = Gravity.CENTER
        orderTextViewParams.setMargins(0, 0, 10, 0)
        orderTextView.layoutParams = orderTextViewParams
        tableRow.addView(orderTextView)

        val difficultyTextView = TextView(context, null, 0, R.style.DefaultText)
        difficultyTextView.textSize = textSize
        if (!headingRow && level != null) {
            difficultyTextView.text = "${df2.format(level.difficultyWeight)}"
        }
        else {
            difficultyTextView.text = "Diff"
        }
        val difficultyTextViewParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        difficultyTextViewParams.gravity = Gravity.CENTER
        difficultyTextViewParams.setMargins(0, 0, 10, 0)
        difficultyTextView.layoutParams = difficultyTextViewParams
        tableRow.addView(difficultyTextView)


        if (!headingRow && level != null) {
            val editLevelMetadata = Button(context, null, 0, R.style.DefaultButton)
            editLevelMetadata.id = level.id
            val editLevelMetadataParams = TableRow.LayoutParams(180, 80)
            editLevelMetadataParams.setMargins(0, 0, 10, 0)
            editLevelMetadata.gravity = Gravity.CENTER
            editLevelMetadata.layoutParams = editLevelMetadataParams
            editLevelMetadata.text = "Edit"
            editLevelMetadata.setOnClickListener(object : OnClickListener {
                override fun onClick(view: View?) {
                    val level = levelManager.levelIdLookup[view!!.id]
                    if (level != null) {
                        parent.showLevelBuilderMetadata(level)
                    }
                    else {
                        "ERROR: invalid level id, ${view.id}"
                    }
                }
            })
            tableRow.addView(editLevelMetadata)


            val levelBuilderButton = ImageView(context)
            levelBuilderButton.id = level.id
            val levelBuilderButtonParams = TableRow.LayoutParams(110, 60)
            levelBuilderButtonParams.setMargins(0, 0, 10, 0)
            levelBuilderButton.layoutParams = levelBuilderButtonParams
            levelBuilderButton.setPadding(50, 0, 0, 0)
            levelBuilderButton.setImageResource(R.drawable.tools_icon)

            levelBuilderButton.setOnClickListener(object : OnClickListener {
                override fun onClick(view: View?) {
                    parent.openLevelById(view!!.id, true)
                }
            })

            tableRow.addView(levelBuilderButton)
        }

        levelsTable.addView(tableRow)
    }
}