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

/** Runtime plumbing for Rambler dictionary preference, learning, and prompt context. */
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

    /** Remembers a real Gboard Context without introducing any process-wide Context hook. */
    public static void observeContext(Context context) {
        if (context == null) {
            return;
        }
        try {
            Context appContext = context.getApplicationContext();
            applicationContext = appContext != null ? appContext : context;
        } catch (Throwable ignored) {
            applicationContext = context;
        }
    }

    /** Keeps the current stock Rambler/Standard selector state. */
    public static void observeSelectionValue(boolean selected) {
        ramblerSelected = Boolean.valueOf(selected);
    }

    /** Restores the stock settings switch from durable state only for its exact backing flag. */
    public static boolean resolveDictionaryPreference(String flagName, boolean originalValue) {
        if (!ENABLE_USER_CONTACT_BIASING.equals(flagName)) {
            return originalValue;
        }

        Boolean persisted = readDictionaryBiasPreference();
        if (persisted != null) {
            dictionaryBiasFallback = persisted;
            return persisted.booleanValue();
        }

        dictionaryBiasFallback = Boolean.valueOf(originalValue);
        writeDictionaryBiasPreference(originalValue);
        return originalValue;
    }

    /** Mirrors only the dictionary OverrideFlagPreference into durable storage. */
    public static void onOverrideFlagChanged(String flagName, boolean value) {
        if (!ENABLE_USER_CONTACT_BIASING.equals(flagName)) {
            return;
        }
        writeDictionaryBiasPreference(value);
    }

    /** Adds personal-dictionary words only to an active Rambler Muse context. */
    public static Collection<?> mergePersonalDictionary(Collection<?> original) {
        if (!Boolean.TRUE.equals(ramblerSelected)) {
            return original;
        }
        if (!isDictionaryBiasEnabled()) {
            return new ArrayList<Object>();
        }

        Context context = applicationContext;
        if (context == null) {
            return original;
        }

        try {
            LinkedHashSet<Object> merged = new LinkedHashSet<Object>();
            if (original != null) {
                merged.addAll(original);
            }
            for (String word : readPersonalDictionaryWords(context)) {
                merged.add(word);
            }
            return new ArrayList<Object>(merged);
        } catch (Throwable ignored) {
            return original;
        }
    }

    /** Records corrections accepted by Gboard's learning controller while Rambler is selected. */
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
            // Learning is additive; storage/reflection failure must not affect dictation.
        }
    }

    private static Boolean readDictionaryBiasPreference() {
        Context context = applicationContext;
        if (context == null) {
            return null;
        }
        try {
            SharedPreferences preferences =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
            // The in-memory value remains effective for the current process.
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
                // An unavailable personal dictionary contributes no extra prompt words.
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
                    if (!containsRamblerWord(database, word)) {
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

    private static boolean containsRamblerWord(SQLiteDatabase database, String word) {
        Cursor cursor = null;
        try {
            cursor = database.query(
                    PERSONAL_DICTIONARY_TABLE,
                    new String[]{"_id"},
                    "word = ? AND shortcut = ?",
                    new String[]{word, RAMBLER_SHORTCUT},
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
