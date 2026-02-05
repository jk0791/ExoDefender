package com.jimjo.exodefender

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet

class PlayerRankingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    data class Model(
        val missionTitle: String,
        val rankings: Networker.MissionRankingsResponse,
        val topLimit: Int,
    )

    private val mainActivity = context as MainActivity

    private lateinit var btnClose: ImageView
    private lateinit var txtTitle: TextView
    private lateinit var txtLocalTitle: TextView
    private lateinit var txtTopTitle: TextView
    private lateinit var txtLocalRankTitle: TextView
    private lateinit var txtTopRankTitle: TextView
    private lateinit var colLocal: LinearLayout
    private lateinit var colTop: LinearLayout
    private lateinit var sectionLocal: View
    private lateinit var middleColSpace: Space


    private val emphasisWhite = 0xFFFFFFFF.toInt()

    init {
        LayoutInflater.from(context).inflate(R.layout.player_rankings_view, this, true)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        btnClose = findViewById(R.id.btnClose)
        txtTitle = findViewById(R.id.txtTitle)
        txtLocalTitle = findViewById(R.id.txtLocalTitle)
        txtTopTitle = findViewById(R.id.txtTopTitle)
        txtLocalRankTitle = findViewById(R.id.txtLocalRankTitle)
        txtTopRankTitle = findViewById(R.id.txtTopRankTitle)
        colLocal = findViewById(R.id.colLocal)
        colTop = findViewById(R.id.colTop)
        sectionLocal = findViewById(R.id.sectionLocal)
        middleColSpace = findViewById(R.id.middleColSpace)

        btnClose.setOnClickListener { visibility = GONE }
    }

    fun show(model: Model) {
        visibility = VISIBLE

        val r = model.rankings
        txtTitle.text = "Player Rankings: " + model.missionTitle

        colLocal.removeAllViews()
        colTop.removeAllViews()

        val inTop = (r.myRank != null && r.myRank <= model.topLimit)

        if (inTop) {
            txtLocalTitle.text = "Chasing Pack"
            txtTopTitle.text = "Your Best Run"
            txtLocalRankTitle.visibility = GONE
            txtTopRankTitle.visibility = VISIBLE
            txtTopRankTitle.text = buildSummary(r)
        }
        else {
            txtLocalTitle.text = "Your Best Run"
            txtTopTitle.text = "Top Runs"
            txtLocalRankTitle.visibility = VISIBLE
            txtTopRankTitle.visibility = GONE
            txtLocalRankTitle.text = buildSummary(r)
        }

        val localSorted = r.local.sortedBy { it.rankNo }
        val topSorted = r.top.sortedBy { it.rankNo }

        // --- Local ---
        val meLocal = localSorted.firstOrNull { it.isMe }
        val meScore = meLocal?.scoreTotal

        localSorted.forEach { row ->
            val delta =
                if (meScore != null && row.rankNo != 1 && !row.isMe) (row.scoreTotal - meScore)
                else null
            colLocal.addView(makeRow(row, delta))
        }

        val leader = topSorted.firstOrNull { it.rankNo == 1 }
        val leaderScore = leader?.scoreTotal

        topSorted.forEach { row ->
            val delta =
                if (inTop && leaderScore != null && row.rankNo != 1) (row.scoreTotal - leaderScore)
                else null
            colTop.addView(makeRow(row, delta))
        }

        sectionLocal.visibility = if (localSorted.isEmpty()) GONE else VISIBLE
        middleColSpace.visibility = if (localSorted.isEmpty()) GONE else VISIBLE
        if (topSorted.isEmpty()) colTop.addView(makePlaceholder("No ranked runs yet"))
    }


    private fun buildSummary(r: Networker.MissionRankingsResponse): String {
        val rankPart =
            if (r.myRank != null && r.totalPlayers > 0) "Rank: #${r.myRank}"
            else "Not ranked"

        val pctPart =
            if (r.topPercent != null && r.totalPlayers > 0) " (Top ${r.topPercent}%)"
            else ""

        return rankPart + pctPart
    }

    private fun makeRow(row: Networker.MissionRankingRow, delta: Int? = null): View {
        val v = LayoutInflater.from(context).inflate(R.layout.player_ranking_row, this, false)
        val txtMarker = v.findViewById<TextView>(R.id.txtMarker)
        val txtRank = v.findViewById<TextView>(R.id.txtRank)
        val txtCallsign = v.findViewById<TextView>(R.id.txtCallsign)
        val txtScore = v.findViewById<TextView>(R.id.txtScore)
        val txtDelta = v.findViewById<TextView>(R.id.txtDelta)

        txtRank.text = "#${row.rankNo}"
        txtCallsign.text = row.callsign
        txtScore.text = "%,d".format(row.scoreTotal)

        // Delta display (vs me)
        if (delta != null) {
            txtDelta.text = formatDelta(delta)
            txtDelta.visibility = VISIBLE
        } else {
            txtDelta.text = ""
            txtDelta.visibility = INVISIBLE
        }

        if (row.isMe) {
            txtMarker.visibility = VISIBLE
            txtRank.setTextColor(emphasisWhite)
            txtCallsign.setTextColor(emphasisWhite)
            txtScore.setTextColor(emphasisWhite)
            txtMarker.setTextColor(emphasisWhite)
            txtDelta.setTextColor(emphasisWhite)
        } else {
            txtMarker.visibility = INVISIBLE
        }

        return v
    }

    private fun formatDelta(d: Int): String {

        // +1,234 / -567 / 0
        val sign = if (d > 0) "+" else ""
        return "$sign${"%,d".format(d)}"
    }

    private fun makePlaceholder(text: String): View {
        val tv = TextView(context)
        tv.text = text
        tv.setTextAppearance(R.style.DefaultText)
        tv.textSize = 18f
        tv.setPadding(22, 14, 0, 14)
        return tv
    }

}
