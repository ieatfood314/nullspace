package ca.heeney.uplink

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Tests run on an emulator with both hubs switched off, so a test device never shows up in,
 *  or writes to, anyone's real data. The app reads these prefs as it starts, so they are set
 *  before its onCreate. */
class OfflineRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        // Gradle removes the app, and its data, from the device after a run. Never a real phone.
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "These tests only run on an emulator (use android/test.sh)" }
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        app.getSharedPreferences("uplink", Context.MODE_PRIVATE).edit()
            .putString("hub_url", "").putString("sync_url", "").putBoolean("setup_seen", true).commit()
        super.callApplicationOnCreate(app)
    }
}
