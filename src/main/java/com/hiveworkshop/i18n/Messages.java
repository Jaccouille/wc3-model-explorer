package com.hiveworkshop.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Centralised i18n helper backed by {@code messages.properties} resource bundles.
 */
public final class Messages {

    private static final String BUNDLE_NAME = "i18n.messages";
    private static ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, Locale.ENGLISH);

    private Messages() {}

    /** (Re-)initialise with the given locale. */
    public static void setLocale(Locale locale) {
        bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale);
    }

    /** Look up a translated string by key. Returns the key itself if not found. */
    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /** Look up a translated pattern and format it with {@link MessageFormat}. */
    public static String fmt(String key, Object... args) {
        return MessageFormat.format(get(key), args);
    }
}
