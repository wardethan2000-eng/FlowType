package com.ethanward.flowtype

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/** Generates the app's whole Material color scheme, light and dark, from the brand blue. */
class FlowtypeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Trace.sink = { level, line ->
            if (level == Trace.Level.WARN) Log.w("Flowtype", line) else Log.i("Flowtype", line)
        }
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder().setContentBasedSource(getColor(R.color.primary)).build(),
        )
    }
}
