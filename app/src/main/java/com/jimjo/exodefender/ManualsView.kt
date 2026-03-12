package com.jimjo.exodefender

import android.content.Context
import android.webkit.WebView
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import com.jimjo.exodefender.ServerConfig.getHostServer

enum class MANUAL_PAGE {BASIC, ADVANCED}
class ManualsView(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    val mainActivity = context as MainActivity

    val webView: WebView

    init {
        inflate(context, R.layout.manuals_view, this)

        val homeButton = findViewById<ImageView>(R.id.btnHome)
        homeButton.setOnClickListener {
            this.visibility = GONE
        }

        webView = findViewById<WebView>(R.id.manualsWebView)
    }

    fun load(pageType: MANUAL_PAGE) {

        val page = when (pageType) {
            MANUAL_PAGE.BASIC -> "/manuals/basic.html"
            MANUAL_PAGE.ADVANCED -> "/manuals/advanced.html"
        }

        val url = getHostServer(mainActivity) + page
        webView.loadUrl(url)
        webView.reload()
    }


}
