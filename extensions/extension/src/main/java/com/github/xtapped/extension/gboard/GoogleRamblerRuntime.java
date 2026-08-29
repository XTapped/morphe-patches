package com.github.xtapped.extension.gboard;

import android.content.Context;

import java.lang.reflect.Method;

/** Keeps Google Rambler capability exposure aligned with Gboard's own voice-typing selector. */
public final class GoogleRamblerRuntime {
    private static final String ENABLE_AGENTIC_DICTATION = "enable_agentic_dictation";

    private static final ThreadLocal<Integer> VOICE_SETTINGS_SCOPE_DEPTH =
            new ThreadLocal<Integer>();
    private static final ThreadLocal<Integer> DEFAULT_SELECTION_SUPPRESSION_DEPTH =
            new ThreadLocal<Integer>();

    private static volatile Boolean ramblerSelected;

    private GoogleRamblerRuntime() {
    }

    public static Object applyFlagValue(String flagName, Object originalResult) {
        if (!ENABLE_AGENTIC_DICTATION.equals(flagName) || !(originalResult instanceof Boolean)) {
            return originalResult;
        }
        return shouldEnableAgenticDictation() ? Boolean.TRUE : originalResult;
    }

    public static void enterVoiceSettingsScope() {
        increment(VOICE_SETTINGS_SCOPE_DEPTH);
    }

    public static void exitVoiceSettingsScope() {
        decrement(VOICE_SETTINGS_SCOPE_DEPTH);
    }

    public static void enterDefaultSelectionSuppression() {
        increment(DEFAULT_SELECTION_SUPPRESSION_DEPTH);
    }

    public static void exitDefaultSelectionSuppression() {
        decrement(DEFAULT_SELECTION_SUPPRESSION_DEPTH);
    }

    public static void updateOfficialSelection(boolean selected) {
        ramblerSelected = Boolean.valueOf(selected);
    }

    private static boolean shouldEnableAgenticDictation() {
        if (depth(DEFAULT_SELECTION_SUPPRESSION_DEPTH) > 0) {
            return false;
        }
        if (depth(VOICE_SETTINGS_SCOPE_DEPTH) > 0) {
            return true;
        }

        Boolean selected = ramblerSelected;
        if (selected == null) {
            selected = readOfficialSelection();
        }
        return Boolean.TRUE.equals(selected);
    }

    private static Boolean readOfficialSelection() {
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (!(application instanceof Context)) {
                return null;
            }

            ClassLoader loader = application.getClass().getClassLoader();
            Class<?> selectorSupport = Class.forName("mqz", false, loader);
            Method selection = selectorSupport.getDeclaredMethod("a", Context.class);
            selection.setAccessible(true);
            Object value = selection.invoke(null, application);
            if (value instanceof Boolean) {
                Boolean resolved = (Boolean) value;
                ramblerSelected = resolved;
                return resolved;
            }
        } catch (Throwable ignored) {
            // Gboard may query the flag before the Application or selector support is ready.
        }
        return null;
    }

    private static void increment(ThreadLocal<Integer> scope) {
        scope.set(Integer.valueOf(depth(scope) + 1));
    }

    private static int depth(ThreadLocal<Integer> scope) {
        Integer value = scope.get();
        return value == null ? 0 : value.intValue();
    }

    private static void decrement(ThreadLocal<Integer> scope) {
        int next = depth(scope) - 1;
        if (next <= 0) {
            scope.remove();
        } else {
            scope.set(Integer.valueOf(next));
        }
    }
}
