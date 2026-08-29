package com.github.xtapped.extension.gboard;

/** Exposes Rambler capability flags while leaving Gboard's stock selector as the activation gate. */
public final class GoogleRamblerRuntime {
    private static final String ENABLE_AGENTIC_DICTATION = "enable_agentic_dictation";
    private static final String CONFIG_AGENTIC_DICTATION = "config_agentic_dictation";
    private static final String ENABLE_JETSON_IN_TOOLBAR = "enable_jetson_in_toolbar";
    private static final String ENABLE_RAMBLER_AL_TOOLBAR = "enable_rambler_al_toolbar";
    private static final String ENABLE_RAMBLER_TOOLBAR_AT_CURSOR_POSITION =
            "enable_rambler_toolbar_at_cursor_position";
    private static final String FILTER_RAMBLER_CONTRIBUTED_INPUT_VIEW_SESSION =
            "filter_rambler_contributed_input_view_session";
    private static final String SHOW_PERSONAL_DICT_BIASING_SETTINGS_TOGGLE =
            "show_personal_dict_biasing_settings_toggle";
    private static final String SHOW_RAMBLER_DICT_SETTINGS = "show_rambler_dict_settings";
    private static final String AD_ACTIVATION_TYPE = "ad_activation_type";

    private static final ThreadLocal<Integer> VOICE_SETTINGS_SCOPE_DEPTH =
            new ThreadLocal<Integer>();
    private static final ThreadLocal<Integer> DEFAULT_SELECTION_SUPPRESSION_DEPTH =
            new ThreadLocal<Integer>();

    private GoogleRamblerRuntime() {
    }

    public static Object applyFlagValue(String flagName, Object originalResult) {
        if (AD_ACTIVATION_TYPE.equals(flagName) && originalResult instanceof Long) {
            return Long.valueOf(2L);
        }
        if (!(originalResult instanceof Boolean)) {
            return originalResult;
        }
        if (SHOW_PERSONAL_DICT_BIASING_SETTINGS_TOGGLE.equals(flagName)
                || SHOW_RAMBLER_DICT_SETTINGS.equals(flagName)
                || CONFIG_AGENTIC_DICTATION.equals(flagName)
                || ENABLE_JETSON_IN_TOOLBAR.equals(flagName)
                || ENABLE_RAMBLER_AL_TOOLBAR.equals(flagName)
                || ENABLE_RAMBLER_TOOLBAR_AT_CURSOR_POSITION.equals(flagName)
                || FILTER_RAMBLER_CONTRIBUTED_INPUT_VIEW_SESSION.equals(flagName)) {
            return Boolean.TRUE;
        }
        if (!ENABLE_AGENTIC_DICTATION.equals(flagName)) {
            return originalResult;
        }

        // Capability exposure must not depend on the user's current Rambler/Standard choice.
        // Gboard's stock runtime eligibility check separately requires the persisted selector.
        // Suppress only the first-run auto-selection path so Standard remains the default.
        return depth(DEFAULT_SELECTION_SUPPRESSION_DEPTH) > 0
                ? Boolean.FALSE
                : Boolean.TRUE;
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
        // The stock selector remains the source of truth; capability exposure is independent.
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
