package com.tokbox.sample.basicvideocapturer

import android.text.TextUtils

object OpenTokConfig {
    /*
    Fill the following variables using your own Project info from the OpenTok dashboard
    https://dashboard.tokbox.com/projects
    */
    // Replace with a API key
    const val API_KEY = ""

    // Replace with a generated Session ID
    const val SESSION_ID = ""

    // Replace with a generated token (from the dashboard or using an OpenTok server SDK)
    const val TOKEN = ""
    val isValid: Boolean
        get() = if (TextUtils.isEmpty(API_KEY)
            || TextUtils.isEmpty(SESSION_ID)
            || TextUtils.isEmpty(TOKEN)
        ) {
            false
        } else true
    val description: String
        get() = """
               OpenTokConfig:
               API_KEY: $API_KEY
               SESSION_ID: $SESSION_ID
               TOKEN: $TOKEN
               
               """.trimIndent()
}