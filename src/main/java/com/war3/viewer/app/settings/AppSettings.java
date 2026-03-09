package com.war3.viewer.app.settings;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Theme;
import javafx.application.Application;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class AppSettings {
    private static AppSettings instance;

    private String gamePath = "";
    private final List<String> mpqPaths = new ArrayList<>();
    private double previewAzimuth = 38.0;
    private double previewElevation = -18.0;
    private String viewerBgColor = "#1a1e26";
    private int    thumbnailSize = 252;
    private String theme         = "NordDark";

    /** All supported AtlantaFX themes in display order: {id, label, dark?}. */
    public static final String[][] THEMES = {
        { "NordDark",       "Nord Dark",        "dark"  },
        { "NordLight",      "Nord Light",       "light" },
        { "Dracula",        "Dracula",          "dark"  },
        { "CupertinoDark",  "Cupertino Dark",   "dark"  },
        { "CupertinoLight", "Cupertino Light",  "light" },
        { "PrimerDark",     "Primer Dark",      "dark"  },
        { "PrimerLight",    "Primer Light",     "light" },
    };

    private AppSettings() {
    }

    public static AppSettings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static void save() {
        if (instance == null) return;
        try {
            final Path file = settingsFile();
            Files.createDirectories(file.getParent());
            final JSONObject json = new JSONObject();
            json.put("gamePath", instance.gamePath);
            final JSONArray mpqs = new JSONArray();
            instance.mpqPaths.forEach(mpqs::put);
            json.put("mpqPaths", mpqs);
            json.put("previewAzimuth", instance.previewAzimuth);
            json.put("previewElevation", instance.previewElevation);
            json.put("viewerBgColor", instance.viewerBgColor);
            json.put("thumbnailSize", instance.thumbnailSize);
            json.put("theme",         instance.theme);
            try (OutputStream out = Files.newOutputStream(file)) {
                out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private static AppSettings load() {
        final AppSettings s = new AppSettings();
        final Path file = settingsFile();
        if (!Files.exists(file)) return s;
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] bytes = in.readAllBytes();
            final JSONObject json = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            s.gamePath = json.optString("gamePath", "");
            final JSONArray mpqs = json.optJSONArray("mpqPaths");
            if (mpqs != null) {
                for (int i = 0; i < mpqs.length(); i++) {
                    s.mpqPaths.add(mpqs.getString(i));
                }
            }
            s.previewAzimuth = json.optDouble("previewAzimuth", 38.0);
            s.previewElevation = json.optDouble("previewElevation", -18.0);
            s.viewerBgColor = json.optString("viewerBgColor", "#1a1e26");
            s.thumbnailSize = json.optInt("thumbnailSize", 252);
            s.theme         = json.optString("theme", "NordDark");
        } catch (final Exception e) {
            e.printStackTrace();
        }
        return s;
    }

    private static Path settingsFile() {
        final String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            return Paths.get(appData, "War3AdvancedModelViewer", "settings.json");
        }
        return Paths.get(System.getProperty("user.home"), ".war3viewer", "settings.json");
    }

    public String getGamePath() { return gamePath; }
    public void setGamePath(final String gamePath) { this.gamePath = gamePath; }

    public List<String> getMpqPaths() { return mpqPaths; }

    public double getPreviewAzimuth() { return previewAzimuth; }
    public void setPreviewAzimuth(final double previewAzimuth) { this.previewAzimuth = previewAzimuth; }

    public double getPreviewElevation() { return previewElevation; }
    public void setPreviewElevation(final double previewElevation) { this.previewElevation = previewElevation; }

    public String getViewerBgColor() { return viewerBgColor; }
    public void setViewerBgColor(final String viewerBgColor) { this.viewerBgColor = viewerBgColor; }

    public int getThumbnailSize() { return thumbnailSize; }
    public void setThumbnailSize(final int thumbnailSize) { this.thumbnailSize = thumbnailSize; }

    public String getTheme() { return theme; }
    public void setTheme(final String theme) { this.theme = theme; }

    /**
     * Instantiates the AtlantaFX theme matching {@code name} and installs it
     * as the global user-agent stylesheet — the JavaFX equivalent of
     * {@code UIManager.setLookAndFeel()} in Swing.
     */
    public static void applyTheme(final String name) {
        final Theme t = switch (name) {
            case "NordLight"      -> new NordLight();
            case "Dracula"        -> new Dracula();
            case "CupertinoDark"  -> new CupertinoDark();
            case "CupertinoLight" -> new CupertinoLight();
            case "PrimerDark"     -> new PrimerDark();
            case "PrimerLight"    -> new PrimerLight();
            default               -> new NordDark(); // "NordDark" + unknown values
        };
        Application.setUserAgentStylesheet(t.getUserAgentStylesheet());
    }
}
