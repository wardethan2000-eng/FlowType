package com.ethanward.flowtype.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.google.android.material.button.MaterialButton

/** Where the mic button lives, and what holding it does. */
class ButtonSettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var reset: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page("Mic button") {
            card {
                val group = RadioGroup(context)
                val options = listOf(
                    false to ("Stay where I put it" to
                        "Hold the button to drag it anywhere on the screen. Tap it to dictate."),
                    true to ("Follow the keyboard" to
                        "Always just above the keyboard. Tap it to dictate, or hold it to talk and let go " +
                        "to type what you said. Slide left before letting go to throw it away."),
                )
                for ((follows, label) in options) {
                    group.addView(RadioButton(context).apply {
                        id = View.generateViewId()
                        text = "${label.first}\n${label.second}"
                        isChecked = prefs.buttonFollowsKeyboard == follows
                        setPadding(dp(8), dp(8), 0, dp(8))
                        setOnCheckedChangeListener { _, on ->
                            if (!on) return@setOnCheckedChangeListener
                            prefs.buttonFollowsKeyboard = follows
                            reset.isEnabled = !follows
                        }
                    })
                }
                addView(group)
            }
            reset = button("Put it back above the keyboard", ButtonKind.OUTLINED) {
                prefs.resetButtonPosition()
                Toast.makeText(this@ButtonSettingsActivity, "The mic button goes back above the keyboard", Toast.LENGTH_SHORT).show()
            }
            reset.isEnabled = !prefs.buttonFollowsKeyboard
            section("While listening")
            card(padded = false) {
                addView(com.google.android.material.materialswitch.MaterialSwitch(context).apply {
                    text = "Pause videos and music"
                    isChecked = prefs.pauseOtherAudio
                    setPadding(dp(16), dp(8), dp(16), 0)
                    setOnCheckedChangeListener { _, on -> prefs.pauseOtherAudio = on }
                })
                text("They pick up again as soon as you tap ✓ or ✕.", secondary = true).setPadding(dp(16), 0, dp(16), dp(12))
            }
        }
    }
}
