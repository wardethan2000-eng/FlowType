package com.ethanward.flowtype

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/** Generates the app's whole Material color scheme, light and dark, from the brand blue. */
class FlowtypeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder().setContentBasedSource(getColor(R.color.primary)).build(),
        )
    }
}
