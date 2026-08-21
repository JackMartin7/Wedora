package com.wedora.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

/**
 * Closes a screen that can be reached either from inside the app or straight
 * from a notification, landing the user in the app rather than out of it.
 *
 * A notification's PendingIntent carries FLAG_ACTIVITY_NEW_TASK (see
 * AppNotifications.show), so the Activity it opens becomes the task ROOT with
 * nothing beneath it. A plain finish() there empties the task and closes the
 * app — from the user's side, tapping a notification and pressing back once
 * throws them out entirely.
 *
 * [AppCompatActivity.isTaskRoot] is what distinguishes the two cases. Reached
 * normally, something is underneath and finish() correctly reveals it; reached
 * from a notification, Home is pushed first so back continues into the app.
 *
 * NOT NavUtils.navigateUpTo or TaskStackBuilder, both of which need
 * parentActivityName — this app declares it nowhere, and AppNotifications'
 * own comment already rejects TaskStackBuilder for exactly that reason. This
 * also matches how ChatThreadActivity and ChatsActivity already handle their
 * own back presses: navigate somewhere real, then finish.
 *
 * CLEAR_TOP so a Home that is somehow already in the task is reused rather
 * than stacked a second time.
 */
fun AppCompatActivity.finishOrGoHome() {
    if (isTaskRoot) {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
    finish()
}
