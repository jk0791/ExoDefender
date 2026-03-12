package com.jimjo.exodefender

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout

class CommunityView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity

    init {
        inflate(context, R.layout.community_view, this)

        findViewById<ImageView>(R.id.btnHome).apply {
            setOnClickListener {
                mainActivity.showHomeView()
            }
        }
        findViewById<ImageView>(R.id.btnDiscord).apply {
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/Jw3nwGu9bA"))
                context.startActivity(intent)
            }
        }
    }
}
