package com.github.xtapped.extension.gboard;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Handles Help & feedback navigation for Rambler settings by launching the
 * voice typing support help destination via browser intent.
 */
public final class GoogleRamblerHelpRuntime {
    private static final String VOICE_TYPING_HELP_URL =
            "https://support.google.com/gboard?p=voice_typing";

    private GoogleRamblerHelpRuntime() {
    }

    /**
     * Directly opens the Gboard voice typing help destination in an external browser.
     * Sets Intent.FLAG_ACTIVITY_NEW_TASK so it can be safely launched from any Context.
     *
     * @param context the Android context from which to start the Activity.
     */
    public static void openVoiceTypingHelp(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(VOICE_TYPING_HELP_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // Gracefully ignore failure so Settings UI does not crash.
        }
    }
}
