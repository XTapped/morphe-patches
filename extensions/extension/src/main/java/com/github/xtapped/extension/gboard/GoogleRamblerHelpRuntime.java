package com.github.xtapped.extension.gboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Routes Rambler settings help through Gboard's stock Help & feedback flow. */
public final class GoogleRamblerHelpRuntime {
    private static final String VOICE_TYPING_HELP_URL =
            "https://support.google.com/gboard?p=voice_typing";

    private static final ThreadLocal<Integer> HELP_SCOPE_DEPTH = new ThreadLocal<Integer>();

    private GoogleRamblerHelpRuntime() {
    }

    /**
     * Runs the same synthetic click handler Gboard uses for its stock Help & feedback entry.
     * The direct URL is only a fallback if that handler cannot be invoked.
     */
    public static void openHelpAndFeedback(Object fragment, Object preference) {
        Context fallbackContext = contextFromPreference(preference);
        if (fallbackContext == null) {
            fallbackContext = contextFromFragment(fragment);
        }
        boolean handled = false;

        enterHelpScope();
        try {
            if (fragment != null && preference != null) {
                ClassLoader loader = fragment.getClass().getClassLoader();
                Class<?> handlerClass = Class.forName("evx", false, loader);
                Constructor<?> constructor =
                        handlerClass.getDeclaredConstructor(Object.class, Integer.TYPE);
                constructor.setAccessible(true);
                Object handler = constructor.newInstance(fragment, Integer.valueOf(6));

                Method click = null;
                for (Method candidate : handlerClass.getDeclaredMethods()) {
                    Class<?>[] parameters = candidate.getParameterTypes();
                    if ("b".equals(candidate.getName())
                            && parameters.length == 1
                            && parameters[0].isInstance(preference)) {
                        click = candidate;
                        break;
                    }
                }
                if (click != null) {
                    click.setAccessible(true);
                    Object result = click.invoke(handler, preference);
                    handled = !(result instanceof Boolean) || ((Boolean) result).booleanValue();
                }
            }
        } catch (Throwable ignored) {
            handled = false;
        } finally {
            exitHelpScope();
        }

        if (!handled) {
            openVoiceTypingHelp(fallbackContext);
        }
    }

    /** Allows an external intent only while the Rambler Help & feedback flow is executing. */
    public static boolean adjustExternalIntentBlock(boolean originalBlocked) {
        return depth() > 0 ? false : originalBlocked;
    }

    private static Context contextFromPreference(Object preference) {
        if (preference == null) {
            return null;
        }
        try {
            Method getContext = preference.getClass().getMethod("getContext");
            Object value = getContext.invoke(preference);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context contextFromFragment(Object fragment) {
        if (fragment == null) {
            return null;
        }
        try {
            Method method = findNoArgMethod(fragment.getClass(), "w");
            if (method != null) {
                Object value = method.invoke(fragment);
                if (value instanceof Context) {
                    return (Context) value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getParameterTypes().length == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static void openVoiceTypingHelp(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(VOICE_TYPING_HELP_URL));
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // The stock handler is the primary path; failure here must not crash Settings.
        }
    }

    private static void enterHelpScope() {
        HELP_SCOPE_DEPTH.set(Integer.valueOf(depth() + 1));
    }

    private static void exitHelpScope() {
        int next = depth() - 1;
        if (next <= 0) {
            HELP_SCOPE_DEPTH.remove();
        } else {
            HELP_SCOPE_DEPTH.set(Integer.valueOf(next));
        }
    }

    private static int depth() {
        Integer value = HELP_SCOPE_DEPTH.get();
        return value == null ? 0 : value.intValue();
    }
}
