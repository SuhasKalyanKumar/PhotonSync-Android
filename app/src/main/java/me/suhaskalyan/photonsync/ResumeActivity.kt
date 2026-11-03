package me.suhaskalyan.photonsync

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible screen that only shows Android's "Start sharing?" dialog, so sync can resume after an unlock
 * with a single tap and drop you back where you were.
 */
class ResumeActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (!SyncState.running || !SyncState.capturePaused) {
            finish()
            return
        }
        startActivityForResult(ScreenSyncService.captureIntent(this), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            ScreenSyncService.start(this, resultCode, data)
        } else {
            SyncState.log("ℹ️ Resume skipped. Tap the PhotonSync notification to resume.")
        }
        finish()
    }
}
