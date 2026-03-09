package com.war3.viewer.app.datasource;

import com.war3.viewer.app.settings.AppSettings;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Singleton facade over the configured CASC / MPQ data sources.
 * Call {@link #refresh()} after changing {@link AppSettings} to rebuild.
 */
public final class GameDataSource {
    private static volatile GameDataSource instance;

    private final CompoundDataSource compound;

    private GameDataSource(final AppSettings settings) {
        final List<DataSource> sources = new ArrayList<>();

        // CASC (Warcraft III Reforged)
        final String gamePath = settings.getGamePath();
        if (gamePath != null && !gamePath.isEmpty()) {
            try {
                sources.add(new CascDataSource(gamePath,
                        new String[]{"_retail_", "_hd.w3mod", "_ptr_"}));
            } catch (final Exception e) {
                System.err.println("Could not open CASC at \"" + gamePath + "\": " + e.getMessage());
            }
        }

        // MPQ archives (legacy)
        for (final String mpqPath : settings.getMpqPaths()) {
            try {
                sources.add(new MpqDataSource(new JMpqEditor(new File(mpqPath), MPQOpenOption.READ_ONLY)));
            } catch (final Exception e) {
                System.err.println("Could not open MPQ at \"" + mpqPath + "\": " + e.getMessage());
            }
        }

        compound = new CompoundDataSource(sources);
    }

    public static GameDataSource get() {
        if (instance == null) {
            synchronized (GameDataSource.class) {
                if (instance == null) {
                    instance = new GameDataSource(AppSettings.get());
                }
            }
        }
        return instance;
    }

    /** Call after the user changes settings to rebuild the data sources. */
    public static void refresh() {
        final GameDataSource old;
        synchronized (GameDataSource.class) {
            old = instance;
            instance = new GameDataSource(AppSettings.get());
        }
        if (old != null) {
            try { old.compound.close(); } catch (final IOException ignored) {}
        }
    }

    public boolean has(final String path) {
        return compound.has(path);
    }

    public InputStream getResourceAsStream(final String path) {
        return compound.getResourceAsStream(path);
    }

    public boolean isEmpty() {
        return compound.isEmpty();
    }
}
