package com.mersal.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val tag = getSharedPreferences("mersal", MODE_PRIVATE).getString("lang", null)
        if (tag != null) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}
