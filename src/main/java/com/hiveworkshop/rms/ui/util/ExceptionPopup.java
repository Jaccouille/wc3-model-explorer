package com.hiveworkshop.rms.ui.util;

import java.util.ArrayList;
import java.util.List;

public final class ExceptionPopup {
    private static final List<String> MESSAGES = new ArrayList<>();
    private static Exception firstException;

    private ExceptionPopup() {
    }

    public static void display(final Throwable throwable) {
        throwable.printStackTrace();
    }

    public static void display(final String message, final Exception exception) {
        System.err.println(message);
        exception.printStackTrace();
    }

    public static void display(final Throwable throwable, final String message) {
        System.err.println(message);
        throwable.printStackTrace();
    }

    public static void addStringToShow(final String message) {
        MESSAGES.add(message);
    }

    public static void clearStringsToShow() {
        MESSAGES.clear();
    }

    public static void setFirstException(final Exception exception) {
        if (firstException == null) {
            firstException = exception;
        }
    }

    public static void clearFirstException() {
        firstException = null;
    }

    public static void displayIfNotEmpty() {
        if (!MESSAGES.isEmpty()) {
            System.err.println("Model parser warnings:");
            for (final String message : MESSAGES) {
                System.err.println(message);
            }
            if (firstException != null) {
                firstException.printStackTrace();
            }
            clearStringsToShow();
            clearFirstException();
        }
    }
}
