package com.github.xtapped.extension.gboard;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Build;
import android.provider.UserDictionary;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.text.BreakIterator;
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

    /**
     * Adds personal-dictionary words only to an active Rambler Muse context.
     * When biasing is enabled, personal dictionary entries are merged with the base collection.
     * When biasing is disabled, the base collection (contacts + name dictionary) is preserved untouched.
     */
    public static Collection<?> mergePersonalDictionary(Collection<?> original) {
        return mergePersonalDictionary(original, null);
    }

    /**
     * Context-aware variant called by patched MuseContextModule to guarantee non-null Context.
     */
    public static Collection<?> mergePersonalDictionary(Collection<?> original, Context context) {
        if (context != null) {
            observeContext(context);
        }
        if (Boolean.FALSE.equals(ramblerSelected)) {
            return original;
        }
        if (!isDictionaryBiasEnabled()) {
            return original != null ? original : new ArrayList<Object>();
        }

        Context ctx = context != null ? context : applicationContext;
        if (ctx == null) {
            return original;
        }

        try {
            LinkedHashSet<Object> merged = new LinkedHashSet<Object>();
            if (original != null) {
                merged.addAll(original);
            }
            for (String word : readPersonalDictionaryWords(ctx)) {
                merged.add(word);
            }
            return new ArrayList<Object>(merged);
        } catch (Throwable ignored) {
            return original;
        }
    }

    /**
     * Automatically extracts and stores words from finalized Rambler voice dictation into
     * PersonalDictionary.db table 'entry' with shortcut = 'rambler'.
     */
    public static void learnRamblerWords(String text, Context context) {
        if (text == null || context == null) {
            return;
        }
        observeContext(context);

        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        try {
            Locale locale = currentLocale(context);
            BreakIterator iterator = BreakIterator.getWordInstance(locale);
            iterator.setText(trimmed);

            LinkedHashSet<String> candidateWords = new LinkedHashSet<String>();
            int start = iterator.first();
            int end = iterator.next();
            while (end != BreakIterator.DONE) {
                String token = trimmed.substring(start, end).trim();
                if (isValidCandidateWord(token)) {
                    candidateWords.add(token);
                }
                start = end;
                end = iterator.next();
            }

            if (!candidateWords.isEmpty()) {
                storeRamblerWords(context, candidateWords);
            }
        } catch (Throwable ignored) {
            // Learning is additive and must never interrupt dictation.
        }
    }

    /**
     * Records corrections accepted by Gboard's learning controller while Rambler is selected.
     * Extracts 'after' tokens from correction instances, normalizes, deduplicates, and saves to
     * PersonalDictionary.db table 'entry' with shortcut = 'rambler'.
     */
    public static void recordRamblerCorrections(Object correctionList) {
        if (correctionList == null || Boolean.FALSE.equals(ramblerSelected)) {
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
                if (correction == null) {
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

    private static boolean isValidCandidateWord(String token) {
        if (token == null || token.length() < 2 || token.length() > 48) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (c != '\'' && c != '-') {
                return false;
            }
        }
        return hasLetter;
    }

    private static ArrayList<String> readPersonalDictionaryWords(Context context) {
        ArrayList<String> words = new ArrayList<String>();
        LinkedHashSet<String> distinct = new LinkedHashSet<String>();

        // 1. Read from Gboard's PersonalDictionary.db table 'entry'
        synchronized (DICTIONARY_LOCK) {
            SQLiteDatabase database = null;
            Cursor cursor = null;
            try {
                database = openDictionaryDatabase(context, false);
                if (database != null) {
                    cursor = database.query(
                            PERSONAL_DICTIONARY_TABLE,
                            new String[]{"word"},
                            null,
                            null,
                            null,
                            null,
                            "word COLLATE NOCASE"
                    );
                    while (cursor.moveToNext()) {
                        String word = normalizeWord(cursor.getString(0));
                        if (word != null) {
                            distinct.add(word);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (database != null) {
                    try {
                        database.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 2. Read from Android System UserDictionary
        try {
            ContentResolver resolver = context.getContentResolver();
            Cursor systemCursor = resolver.query(
                    UserDictionary.Words.CONTENT_URI,
                    new String[]{UserDictionary.Words.WORD},
                    null,
                    null,
                    null
            );
            if (systemCursor != null) {
                try {
                    while (systemCursor.moveToNext()) {
                        String word = normalizeWord(systemCursor.getString(0));
                        if (word != null) {
                            distinct.add(word);
                        }
                    }
                } finally {
                    systemCursor.close();
                }
            }
        } catch (Throwable ignored) {
        }

        words.addAll(distinct);
        return words;
    }

    private static void storeRamblerWords(Context context, Collection<String> words) {
        synchronized (DICTIONARY_LOCK) {
            SQLiteDatabase database = null;
            try {
                database = openDictionaryDatabase(context, true);
                if (database == null) {
                    return;
                }
                String locale = currentLocaleTag(context);
                database.beginTransaction();
                boolean anyInserted = false;
                for (String word : words) {
                    String normalized = normalizeWord(word);
                    if (normalized != null && !containsWord(database, normalized)) {
                        ContentValues values = new ContentValues();
                        values.put("word", normalized);
                        values.put("shortcut", RAMBLER_SHORTCUT);
                        values.put("locale", locale);
                        database.insert(PERSONAL_DICTIONARY_TABLE, null, values);
                        anyInserted = true;
                    }
                }
                database.setTransactionSuccessful();
                if (anyInserted) {
                    try {
                        context.getContentResolver().notifyChange(
                                Uri.parse("content://com.google.android.inputmethod.latin.personaldictionary/entry"),
                                null
                        );
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
                // Learning is best-effort and must never interrupt the voice session.
            } finally {
                if (database != null) {
                    if (database.inTransaction()) {
                        try {
                            database.endTransaction();
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        database.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static boolean containsWord(SQLiteDatabase database, String word) {
        Cursor cursor = null;
        try {
            cursor = database.query(
                    PERSONAL_DICTIONARY_TABLE,
                    new String[]{"_id"},
                    "word = ? COLLATE NOCASE",
                    new String[]{word},
                    null,
                    null,
                    null,
                    "1"
            );
            return cursor != null && cursor.moveToFirst();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static SQLiteDatabase openDictionaryDatabase(Context context, boolean writable) {
        try {
            SQLiteOpenHelper helper = createPersonalDictionaryHelper(context);
            return writable ? helper.getWritableDatabase() : helper.getReadableDatabase();
        } catch (Throwable t1) {
            try {
                File dbFile = context.getDatabasePath("PersonalDictionary.db");
                if (writable) {
                    SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS " + PERSONAL_DICTIONARY_TABLE + " ("
                                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                    + "word TEXT, "
                                    + "shortcut TEXT, "
                                    + "locale TEXT)"
                    );
                    return db;
                } else if (dbFile.exists()) {
                    return SQLiteDatabase.openDatabase(
                            dbFile.getPath(),
                            null,
                            SQLiteDatabase.OPEN_READONLY
                    );
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
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

    private static Locale currentLocale(Context context) {
        try {
            Configuration configuration = context.getResources().getConfiguration();
            if (Build.VERSION.SDK_INT >= 24 && !configuration.getLocales().isEmpty()) {
                return configuration.getLocales().get(0);
            } else {
                return configuration.locale;
            }
        } catch (Throwable ignored) {
        }
        return Locale.getDefault();
    }

    private static String currentLocaleTag(Context context) {
        Locale locale = currentLocale(context);
        if (locale != null) {
            return locale.toLanguageTag();
        }
        return Locale.getDefault().toLanguageTag();
    }
}
