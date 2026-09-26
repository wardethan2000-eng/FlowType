package com.ethanward.flowtype.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** The Phase 0 measuring tools, out of the way of the everyday settings. */
class DeveloperActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Developer tools") {
            card(padded = false) {
                navRow("Speech bench", "Load time, speed, memory and accuracy for each model") {
                    startActivity(Intent(this@DeveloperActivity, BenchActivity::class.java))
                }
                divider()
                navRow("Insertion test", "Test fields, test-phrase mode, and the insertion log") {
                    startActivity(Intent(this@DeveloperActivity, InsertionTestActivity::class.java))
                }
                divider()
                navRow("Cleanup timing", "Cold and warm timing of each cleanup model") {
                    startActivity(Intent(this@DeveloperActivity, CleanupTestActivity::class.java))
                }
            }
        }
    }
}
