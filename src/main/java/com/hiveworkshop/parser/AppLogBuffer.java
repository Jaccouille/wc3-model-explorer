package com.hiveworkshop.parser;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures System.out and System.err into an in-memory ring buffer
 * so that logs can be displayed in the Settings dialog for debugging.
 */
public final class AppLogBuffer {
    private static final int MAX_LINES = 2000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final List<String> lines = new CopyOnWriteArrayList<>();
    private static PrintStream originalOut;
    private static PrintStream originalErr;

    private AppLogBuffer() {}

    /** Call once at startup, before any logging happens. */
    public static void install() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new TeeStream(originalOut, "OUT"));
        System.setErr(new TeeStream(originalErr, "ERR"));
    }

    /** Returns a snapshot of all captured log lines. */
    public static List<String> getLines() {
        return new ArrayList<>(lines);
    }

    /** Returns all captured log lines as a single string. */
    public static String getText() {
        return String.join("\n", lines);
    }

    /** Clears all captured log lines. */
    public static void clear() {
        lines.clear();
    }

    private static void addLine(String level, String text) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String line = timestamp + " [" + level + "] " + text;
        lines.add(line);
        // Trim oldest lines if buffer is full
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
    }

    private static final class TeeStream extends PrintStream {
        private final String level;

        TeeStream(PrintStream original, String level) {
            super(original, true);
            this.level = level;
        }

        @Override
        public void println(String x) {
            super.println(x);
            if (x != null) addLine(level, x);
        }

        @Override
        public void println(Object x) {
            super.println(x);
            if (x != null) addLine(level, x.toString());
        }

        @Override
        public void print(String s) {
            super.print(s);
            // Only capture complete lines via println
        }
    }
}
