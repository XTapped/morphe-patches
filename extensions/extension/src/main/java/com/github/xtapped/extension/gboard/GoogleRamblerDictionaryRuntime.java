package com.github.xtapped.extension.gboard;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;

/** Runtime plumbing for Rambler's dictionary and settings-only behavior. */
public final class GoogleRamblerDictionaryRuntime {
    private static final String ENABLE_USER_CONTACT_BIASING = "enable_user_contact_biasing";
    private static final String PREFS_NAME = "xtapped_google_rambler";
    private static final String PREF_DICTIONARY_BIAS = "use_dictionary_words";
    private static final String RAMBLER_SHORTCUT = "rambler";
    private static final String PERSONAL_DICTIONARY_TABLE = "entry";

    private static final Object DICTIONARY_LOCK = new Object();

    private static volatile Context applicationContext;
    private static volatile Boolean ramblerSelected;
    private static volatile Boolean dictionaryBiasFallback;

    private GoogleRamblerDictionaryRuntime() {
    }

    /** Captures the context and selector value from Gboard's own stock selection reader. */
    public static void observeSelection(Context context, boolean selected) {
        rememberContext(context);
        observeSelectionValue(selected);

        if (readDictionaryBiasPreference() == null && dictionaryBiasFallback != null) {
            writeDictionaryBiasPreference(dictionaryBiasFallback.booleanValue());
        }
    }

    /** Keeps selection state current immediately when the stock selector is changed. */
    public static void observeSelectionValue(boolean selected) {
        ramblerSelected = Boolean.valueOf(selected);
    }

    /** Supplies a non-keyboard context to the stock Rambler feedback access point. */
    public static Context getApplicationContext() {
        return applicationContext;
    }

    /** Applies the persistent value behind the stock "Use dictionary words" switch. */
    public static Object applyDictionaryFlagValue(String flagName, Object originalResult) {
        if (!ENABLE_USER_CONTACT_BIASING.equals(flagName) || !(originalResult instanceof Boolean)) {
            return originalResult;
        }

        dictionaryBiasFallback = (Boolean) originalResult;
        Boolean persisted = readDictionaryBiasPreference();
        if (persisted != null) {
            dictionaryBiasFallback = persisted;
            return persisted;
        }

        // Seed our persistent setting from Gboard's resolved value the first time the flag
        // is observed. The in-memory fallback keeps the same value effective even if Gboard
        // resolves the flag before a Context is available for SharedPreferences.
        writeDictionaryBiasPreference(((Boolean) originalResult).booleanValue());
        return originalResult;
    }

    /** Mirrors OverrideFlagPreference changes into storage that works on production Gboard. */
    public static void onOverrideFlagChanged(Object preference, boolean value) {
        dictionaryBiasFallback = Boolean.valueOf(value);
        if (preference == null) {
            writeDictionaryBiasPreference(value);
            return;
        }
        try {
            Object context = readField(preference, "j");
            if (context instanceof Context) {
                rememberContext((Context) context);
            }
            Object flagName = readField(preference, "r");
            if (ENABLE_USER_CONTACT_BIASING.equals(flagName)) {
                writeDictionaryBiasPreference(value);
            }
        } catch (Throwable ignored) {
            // Gboard's own preference implementation remains the fallback.
        }
    }

    /** Adds Gboard's saved and Rambler-learned words to Muse's personal dictionary context. */
    public static Collection<?> mergePersonalDictionary(Collection<?> original) {
        if (!isDictionaryBiasEnabled()) {
            return original;
        }

        LinkedHashSet<Object> merged = new LinkedHashSet<Object>();
        if (original != null) {
            merged.addAll(original);
        }

        Context context = applicationContext;
        if (context == null) {
            return new ArrayList<Object>(merged);
        }

        for (String word : readPersonalDictionaryWords(context)) {
            merged.add(word);
        }
        return new ArrayList<Object>(merged);
    }

    /** Records eligible Rambler corrections in the database used by Rambler Dictionary. */
    public static void recordRamblerCorrections(Object correctionList) {
        if (correctionList == null || !Boolean.TRUE.equals(ramblerSelected)) {
            return;
        }

        Context context = applicationContext;
        if (context == null) {
            return;
        }

        try {
            Object rawCorrections = readField(correctionList, "b");
            if (!(rawCorrections instanceof Iterable<?>)) {
                return;
            }

            LinkedHashSet<String> learned = new LinkedHashSet<String>();
            for (Object correction : (Iterable<?>) rawCorrections) {
                if (correction == null || !readBooleanField(correction, "h")) {
                    continue;
                }

                String before = normalizeWord(readNestedString(correction, "c", "b"));
                String after = normalizeWord(readNestedString(correction, "d", "b"));
                if (after == null || after.equals(before)) {
                    continue;
                }
                learned.add(after);
            }

            if (!learned.isEmpty()) {
                storeRamblerWords(context, learned);
            }
        } catch (Throwable ignored) {
            // Learning is additive; a storage failure must never break dictation.
        }
    }

    private static void rememberContext(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        applicationContext = appContext != null ? appContext : context;
    }

    private static Boolean readDictionaryBiasPreference() {
        Context context = applicationContext;
        if (context == null) {
            return null;
        }
        try {
            SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (!preferences.contains(PREF_DICTIONARY_BIAS)) {
                return null;
            }
            return Boolean.valueOf(preferences.getBoolean(PREF_DICTIONARY_BIAS, false));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeDictionaryBiasPreference(boolean value) {
        dictionaryBiasFallback = Boolean.valueOf(value);
        Context context = applicationContext;
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_DICTIONARY_BIAS, value)
                    .apply();
        } catch (Throwable ignored) {
            // The in-memory resolved value remains the fallback if storage is unavailable.
        }
    }

    private static boolean isDictionaryBiasEnabled() {
        Boolean persisted = readDictionaryBiasPreference();
        if (persisted != null) {
            dictionaryBiasFallback = persisted;
            return persisted.booleanValue();
        }
        return Boolean.TRUE.equals(dictionaryBiasFallback);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        return findField(target.getClass(), fieldName).get(target);
    }

    private static boolean readBooleanField(Object target, String fieldName) throws Exception {
        return findField(target.getClass(), fieldName).getBoolean(target);
    }

    private static String readNestedString(
            Object target,
            String objectFieldName,
            String stringFieldName
    ) throws Exception {
        Object nested = readField(target, objectFieldName);
        if (nested == null) {
            return null;
        }
        Object value = readField(nested, stringFieldName);
        return value instanceof String ? (String) value : null;
    }

    private static String normalizeWord(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 256) {
            return null;
        }
        return trimmed;
    }

    private static ArrayList<String> readPersonalDictionaryWords(Context context) {
        ArrayList<String> words = new ArrayList<String>();
        synchronized (DICTIONARY_LOCK) {
            SQLiteOpenHelper helper = null;
            Cursor cursor = null;
            try {
                helper = createPersonalDictionaryHelper(context);
                SQLiteDatabase database = helper.getReadableDatabase();
                cursor = database.query(
                        PERSONAL_DICTIONARY_TABLE,
                        new String[]{"word"},
                        null,
                        null,
                        null,
                        null,
                        "word COLLATE NOCASE"
                );
                LinkedHashSet<String> distinct = new LinkedHashSet<String>();
                while (cursor.moveToNext()) {
                    String word = normalizeWord(cursor.getString(0));
                    if (word != null) {
                        distinct.add(word);
                    }
                }
                words.addAll(distinct);
            } catch (Throwable ignored) {
                // An unavailable personal dictionary simply contributes no prompt words.
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (helper != null) {
                    try {
                        helper.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return words;
    }

    private static void storeRamblerWords(Context context, Collection<String> words) {
        synchronized (DICTIONARY_LOCK) {
            SQLiteOpenHelper helper = null;
            SQLiteDatabase database = null;
            try {
                helper = createPersonalDictionaryHelper(context);
                database = helper.getWritableDatabase();
                String locale = currentLocaleTag(context);
                database.beginTransaction();
                for (String word : words) {
                    if (!containsRamblerWord(database, word, locale)) {
                        ContentValues values = new ContentValues();
                        values.put("word", word);
                        values.put("shortcut", RAMBLER_SHORTCUT);
                        values.put("locale", locale);
                        database.insert(PERSONAL_DICTIONARY_TABLE, null, values);
                    }
                }
                database.setTransactionSuccessful();
            } catch (Throwable ignored) {
                // Learning is best-effort and must never interrupt the voice session.
            } finally {
                if (database != null && database.inTransaction()) {
                    try {
                        database.endTransaction();
                    } catch (Throwable ignored) {
                    }
                }
                if (helper != null) {
                    try {
                        helper.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static boolean containsRamblerWord(
            SQLiteDatabase database,
            String word,
            String locale
    ) {
        Cursor cursor = null;
        try {
            cursor = database.query(
                    PERSONAL_DICTIONARY_TABLE,
                    new String[]{"_id"},
                    "word = ? AND shortcut = ? AND locale = ?",
                    new String[]{word, RAMBLER_SHORTCUT, locale},
                    null,
                    null,
                    null,
                    "1"
            );
            return cursor.moveToFirst();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static SQLiteOpenHelper createPersonalDictionaryHelper(Context context) throws Exception {
        Class<?> helperClass = Class.forName("qcv", false, context.getClassLoader());
        Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        Object helper = constructor.newInstance(context);
        if (!(helper instanceof SQLiteOpenHelper)) {
            throw new IllegalStateException("Unexpected Gboard personal dictionary helper");
        }
        return (SQLiteOpenHelper) helper;
    }

    private static String currentLocaleTag(Context context) {
        try {
            Configuration configuration = context.getResources().getConfiguration();
            Locale locale;
            if (Build.VERSION.SDK_INT >= 24 && !configuration.getLocales().isEmpty()) {
                locale = configuration.getLocales().get(0);
            } else {
                locale = configuration.locale;
            }
            if (locale != null) {
                return locale.toLanguageTag();
            }
        } catch (Throwable ignored) {
        }
        return Locale.getDefault().toLanguageTag();
    }
}
