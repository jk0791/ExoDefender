package com.jimjo.exodefender

import android.content.Context

object ServerConfig {
    fun getHostServer(context: Context): String {
        val sp = context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
        return sp.getString(OVERRIDE_HOST_SERVER, BuildConfig.DEFAULT_HOST_SERVER)
            ?: BuildConfig.DEFAULT_HOST_SERVER
    }

    fun setOverrideHostServer(context: Context, host: String?) {
        context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (host.isNullOrBlank()) remove(OVERRIDE_HOST_SERVER)
                else putString(OVERRIDE_HOST_SERVER, host)
            }
            .apply()
    }
    fun clearOverrideHostServer(context: Context) {
        setOverrideHostServer(context, null)
    }
}
