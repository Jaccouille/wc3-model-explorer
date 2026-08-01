package com.hiveworkshop.parser;

import com.hiveworkshop.model.TeamColorOptions;
import com.hiveworkshop.model.ExternalProgram;
import com.hiveworkshop.model.PortraitFilter;
import com.hiveworkshop.model.ThumbnailQuality;
import com.hiveworkshop.model.ThumbnailSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class AppSettings {
    private static final String KEY_LAST_ROOT_DIRECTORY = "lastRootDirectory";
    private static final String KEY_PORTRAIT_FILTER     = "portraitFilter";
    private static final String KEY_THUMBNAIL_SIZE      = "thumbnailSize";
    private static final String KEY_CASC_PATH           = "cascPath";
    private static final String KEY_MPQ_PATHS           = "mpqPaths";
    private static final String KEY_THEME               = "theme";
    private static final String KEY_BG_COLOR            = "bgColor";
    private static final String KEY_CAMERA_YAW          = "cameraYaw";
    private static final String KEY_CAMERA_PITCH        = "cameraPitch";
    private static final String KEY_THUMBNAIL_ANIM      = "thumbnailAnimName";
    private static final String KEY_THUMBNAIL_QUALITY   = "thumbnailQuality";
    private static final String KEY_THUMBNAIL_TEAM_COLOR = "thumbnailTeamColor";
    private static final String KEY_RECENT_FOLDERS      = "recentFolders";
    private static final String KEY_FAVORITES            = "favorites";
    private static final String SETTINGS_FILE_NAME      = "settings.properties";
    private static final int MAX_RECENT_FOLDERS          = 15;

    public static final float DEFAULT_CAMERA_YAW       = 200f;
    public static final float DEFAULT_CAMERA_PITCH    = 20f;

    private final Path settingsPath;
    private String      lastRootDirectory = "";
    private PortraitFilter portraitFilter = PortraitFilter.MODELS_ONLY;
    private ThumbnailSize  thumbnailSize  = ThumbnailSize.MEDIUM;
    private String         cascPath       = "";
    private List<String>   mpqPaths       = new ArrayList<>();
    private String         theme          = "";
    private String         bgColor        = "0F1419"; // dark blue-grey default
    private float          cameraYaw      = DEFAULT_CAMERA_YAW;
    private float          cameraPitch    = DEFAULT_CAMERA_PITCH;
    private String         thumbnailAnimName = "Stand";
    private ThumbnailQuality thumbnailQuality = ThumbnailQuality.MEDIUM;
    private int            thumbnailTeamColor = 0;
    private List<ExternalProgram> externalPrograms = new ArrayList<>();
    private List<String> recentFolders = new ArrayList<>();
    private java.util.Set<String> favorites = new java.util.LinkedHashSet<>();
    private String locale = "";
    private boolean tagsEnabled = true;
    private java.util.Set<String> removedTags = new java.util.LinkedHashSet<>();
    private java.util.Map<String, java.util.Set<String>> customModelTags = new java.util.LinkedHashMap<>();
    // Model-viewer control state (grid/overlays/shading/speed/etc.), persisted so the
    // next opened model reuses the last settings. Stored as simple key=value strings.
    private java.util.Map<String, String> viewerPrefs = new java.util.LinkedHashMap<>();
    // General UI state (window sizes, main-window filter selections). Same simple
    // key=value design as viewerPrefs, persisted under the "ui." property prefix.
    private java.util.Map<String, String> uiPrefs = new java.util.LinkedHashMap<>();

    private AppSettings(Path settingsPath) {
        this.settingsPath = settingsPath;
    }

    public static AppSettings loadDefault() {
        String appData = System.getenv("APPDATA");
        Path baseDir;
        if (appData == null || appData.isBlank()) {
            baseDir = Path.of(System.getProperty("user.home"), ".wc3-model-explorer");
        } else {
            baseDir = Path.of(appData, "wc3-model-explorer");
        }

        AppSettings settings = new AppSettings(baseDir.resolve(SETTINGS_FILE_NAME));
        settings.loadFromDisk();
        return settings;
    }

    public String lastRootDirectory() {
        return lastRootDirectory;
    }

    public PortraitFilter portraitFilter() {
        return portraitFilter;
    }

    public void setLastRootDirectory(String lastRootDirectory) {
        this.lastRootDirectory = lastRootDirectory == null ? "" : lastRootDirectory;
    }

    public void setPortraitFilter(PortraitFilter portraitFilter) {
        this.portraitFilter = portraitFilter == null ? PortraitFilter.MODELS_ONLY : portraitFilter;
    }

    public ThumbnailSize thumbnailSize() {
        return thumbnailSize;
    }

    public void setThumbnailSize(ThumbnailSize thumbnailSize) {
        this.thumbnailSize = thumbnailSize == null ? ThumbnailSize.MEDIUM : thumbnailSize;
    }

    public String cascPath() {
        return cascPath;
    }

    public void setCascPath(String cascPath) {
        this.cascPath = cascPath == null ? "" : cascPath.trim();
    }

    public List<String> mpqPaths() {
        return new ArrayList<>(mpqPaths);
    }

    public void setMpqPaths(List<String> mpqPaths) {
        this.mpqPaths = mpqPaths == null ? new ArrayList<>() : new ArrayList<>(mpqPaths);
    }

    public String theme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme == null ? "" : theme;
    }

    public String bgColor() {
        return bgColor;
    }

    public void setBgColor(String bgColor) {
        this.bgColor = bgColor == null ? "0F1419" : bgColor;
    }

    public float cameraYaw() {
        return cameraYaw;
    }

    public void setCameraYaw(float cameraYaw) {
        this.cameraYaw = cameraYaw;
    }

    public float cameraPitch() {
        return cameraPitch;
    }

    public void setCameraPitch(float cameraPitch) {
        this.cameraPitch = cameraPitch;
    }

    public String thumbnailAnimName() {
        return thumbnailAnimName;
    }

    public void setThumbnailAnimName(String name) {
        this.thumbnailAnimName = name == null ? "Stand" : name.trim();
    }

    public ThumbnailQuality thumbnailQuality() {
        return thumbnailQuality;
    }

    public void setThumbnailQuality(ThumbnailQuality quality) {
        this.thumbnailQuality = quality == null ? ThumbnailQuality.MEDIUM : quality;
    }

    public int thumbnailTeamColor() {
        return thumbnailTeamColor;
    }

    public void setThumbnailTeamColor(int thumbnailTeamColor) {
        this.thumbnailTeamColor = Math.max(0, Math.min(TeamColorOptions.COUNT - 1, thumbnailTeamColor));
    }

    public List<ExternalProgram> externalPrograms() {
        return new ArrayList<>(externalPrograms);
    }

    public void setExternalPrograms(List<ExternalProgram> programs) {
        this.externalPrograms = programs == null ? new ArrayList<>() : new ArrayList<>(programs);
    }

    public List<String> recentFolders() { return new ArrayList<>(recentFolders); }

    public void addRecentFolder(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return;
        recentFolders.remove(absolutePath);
        recentFolders.add(0, absolutePath);
        if (recentFolders.size() > MAX_RECENT_FOLDERS) {
            recentFolders = new ArrayList<>(recentFolders.subList(0, MAX_RECENT_FOLDERS));
        }
    }

    public boolean isFavorite(String absolutePath) {
        return absolutePath != null && favorites.contains(absolutePath);
    }

    public void toggleFavorite(String absolutePath) {
        if (absolutePath == null) return;
        if (!favorites.remove(absolutePath)) favorites.add(absolutePath);
    }

    public java.util.Set<String> favorites() { return new java.util.LinkedHashSet<>(favorites); }

    public String locale() { return locale; }

    public void setLocale(String locale) {
        this.locale = (locale == null) ? "" : locale;
    }

    public boolean tagsEnabled() { return tagsEnabled; }

    public void setTagsEnabled(boolean tagsEnabled) { this.tagsEnabled = tagsEnabled; }

    public java.util.Set<String> removedTags() { return new java.util.LinkedHashSet<>(removedTags); }

    public void addRemovedTag(String tag) {
        if (tag != null && !tag.isBlank()) removedTags.add(tag.trim());
    }

    public void removeRemovedTag(String tag) {
        if (tag != null) removedTags.remove(tag.trim());
    }

    public void setRemovedTags(java.util.Set<String> tags) {
        this.removedTags = tags == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(tags);
    }

    public boolean isTagRemoved(String tag) {
        return tag != null && removedTags.contains(tag.trim());
    }

    public java.util.Set<String> getCustomTags(String modelPath) {
        java.util.Set<String> tags = customModelTags.get(modelPath);
        return tags == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(tags);
    }

    public void addCustomTag(String modelPath, String tag) {
        if (modelPath == null || tag == null || tag.isBlank()) return;
        customModelTags.computeIfAbsent(modelPath, k -> new java.util.LinkedHashSet<>()).add(tag.trim());
    }

    public void removeCustomTag(String modelPath, String tag) {
        if (modelPath == null || tag == null) return;
        java.util.Set<String> tags = customModelTags.get(modelPath);
        if (tags != null) {
            tags.remove(tag.trim());
            if (tags.isEmpty()) customModelTags.remove(modelPath);
        }
    }

    public java.util.Set<String> allCustomTagNames() {
        java.util.Set<String> all = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (java.util.Set<String> tags : customModelTags.values()) {
            all.addAll(tags);
        }
        return all;
    }

    // ── Model-viewer control preferences ─────────────────────────────────
    public boolean viewerBool(String key, boolean def) {
        String v = viewerPrefs.get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public int viewerInt(String key, int def) {
        String v = viewerPrefs.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException ignored) { return def; }
    }

    public void setViewerPref(String key, boolean value) { viewerPrefs.put(key, String.valueOf(value)); }

    public void setViewerPref(String key, int value) { viewerPrefs.put(key, String.valueOf(value)); }

    // ── General UI preferences (window sizes, main-window filters) ───────
    public String uiString(String key, String def) {
        String v = uiPrefs.get(key);
        return v == null ? def : v;
    }

    public int uiInt(String key, int def) {
        String v = uiPrefs.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException ignored) { return def; }
    }

    public boolean uiBool(String key, boolean def) {
        String v = uiPrefs.get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public void setUiPref(String key, String value) {
        if (value == null) uiPrefs.remove(key); else uiPrefs.put(key, value);
    }

    public void setUiPref(String key, int value) { uiPrefs.put(key, String.valueOf(value)); }

    public void setUiPref(String key, boolean value) { uiPrefs.put(key, String.valueOf(value)); }

    public void save() {
        Properties properties = new Properties();
        properties.setProperty(KEY_LAST_ROOT_DIRECTORY, lastRootDirectory);
        properties.setProperty(KEY_PORTRAIT_FILTER, portraitFilter.name());
        properties.setProperty(KEY_THUMBNAIL_SIZE, thumbnailSize.name());
        properties.setProperty(KEY_CASC_PATH, cascPath);
        properties.setProperty(KEY_MPQ_PATHS, String.join("|", mpqPaths));
        properties.setProperty(KEY_THEME, theme);
        properties.setProperty(KEY_BG_COLOR, bgColor);
        properties.setProperty(KEY_CAMERA_YAW, String.valueOf(cameraYaw));
        properties.setProperty(KEY_CAMERA_PITCH, String.valueOf(cameraPitch));
        properties.setProperty(KEY_THUMBNAIL_ANIM, thumbnailAnimName);
        properties.setProperty(KEY_THUMBNAIL_QUALITY, thumbnailQuality.name());
        properties.setProperty(KEY_THUMBNAIL_TEAM_COLOR, String.valueOf(thumbnailTeamColor));
        for (int i = 0; i < externalPrograms.size(); i++) {
            ExternalProgram p = externalPrograms.get(i);
            properties.setProperty("external.program." + i + ".name", p.name());
            properties.setProperty("external.program." + i + ".command", p.command());
            properties.setProperty("external.program." + i + ".arguments", p.arguments());
        }
        properties.setProperty("external.program.count", String.valueOf(externalPrograms.size()));
        properties.setProperty(KEY_RECENT_FOLDERS, String.join("|", recentFolders));
        properties.setProperty(KEY_FAVORITES, String.join("|", favorites));
        properties.setProperty("locale", locale);
        properties.setProperty("tagsEnabled", String.valueOf(tagsEnabled));
        properties.setProperty("removedTags", String.join("|", removedTags));

        int cti = 0;
        for (var entry : customModelTags.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                properties.setProperty("customTag." + cti + ".path", entry.getKey());
                properties.setProperty("customTag." + cti + ".tags", String.join("|", entry.getValue()));
                cti++;
            }
        }
        properties.setProperty("customTag.count", String.valueOf(cti));

        for (var entry : viewerPrefs.entrySet()) {
            properties.setProperty("viewerpref." + entry.getKey(), entry.getValue());
        }

        for (var entry : uiPrefs.entrySet()) {
            properties.setProperty("ui." + entry.getKey(), entry.getValue());
        }

        try {
            Path parent = settingsPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(settingsPath)) {
                properties.store(output, "WC3 Model Explorer settings");
            }
        } catch (IOException ignored) {
            // Keep app usable even if settings cannot be written.
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(settingsPath)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(settingsPath)) {
            properties.load(input);
            lastRootDirectory = properties.getProperty(KEY_LAST_ROOT_DIRECTORY, "");
            String filterValue = properties.getProperty(KEY_PORTRAIT_FILTER, PortraitFilter.MODELS_ONLY.name());
            portraitFilter = parsePortraitFilter(filterValue);
            String thumbnailValue = properties.getProperty(KEY_THUMBNAIL_SIZE, ThumbnailSize.MEDIUM.name());
            thumbnailSize = parseThumbnailSize(thumbnailValue);
            cascPath = properties.getProperty(KEY_CASC_PATH, "");
            String mpqRaw = properties.getProperty(KEY_MPQ_PATHS, "");
            mpqPaths = mpqRaw.isEmpty() ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList(mpqRaw.split("\\|")));
            theme = properties.getProperty(KEY_THEME, "");
            bgColor = properties.getProperty(KEY_BG_COLOR, "0F1419");
            cameraYaw = parseFloat(properties.getProperty(KEY_CAMERA_YAW), DEFAULT_CAMERA_YAW);
            cameraPitch = parseFloat(properties.getProperty(KEY_CAMERA_PITCH), DEFAULT_CAMERA_PITCH);
            thumbnailAnimName = properties.getProperty(KEY_THUMBNAIL_ANIM, "Stand");
            String qualityVal = properties.getProperty(KEY_THUMBNAIL_QUALITY, ThumbnailQuality.MEDIUM.name());
            thumbnailQuality = parseThumbnailQuality(qualityVal);
            thumbnailTeamColor = Math.max(0, Math.min(TeamColorOptions.COUNT - 1,
                    parseInt(properties.getProperty(KEY_THUMBNAIL_TEAM_COLOR), 0)));
            String recentRaw = properties.getProperty(KEY_RECENT_FOLDERS, "");
            recentFolders = recentRaw.isEmpty() ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList(recentRaw.split("\\|")));
            String favRaw = properties.getProperty(KEY_FAVORITES, "");
            favorites = favRaw.isEmpty() ? new java.util.LinkedHashSet<>()
                    : new java.util.LinkedHashSet<>(Arrays.asList(favRaw.split("\\|")));
            int progCount = parseInt(properties.getProperty("external.program.count"), 0);
            externalPrograms = new ArrayList<>();
            for (int i = 0; i < progCount; i++) {
                String pName = properties.getProperty("external.program." + i + ".name", "");
                String pCmd = properties.getProperty("external.program." + i + ".command", "");
                // Backwards compat: fall back to old "path" key
                if (pCmd.isBlank()) pCmd = properties.getProperty("external.program." + i + ".path", "");
                String pArgs = properties.getProperty("external.program." + i + ".arguments", "");
                if (!pName.isBlank() && !pCmd.isBlank()) {
                    externalPrograms.add(new ExternalProgram(pName, pCmd, pArgs));
                }
            }
            locale = properties.getProperty("locale", "");
            tagsEnabled = !"false".equalsIgnoreCase(properties.getProperty("tagsEnabled", "true"));
            String removedTagsRaw = properties.getProperty("removedTags", "");
            removedTags = removedTagsRaw.isEmpty() ? new java.util.LinkedHashSet<>()
                    : new java.util.LinkedHashSet<>(Arrays.asList(removedTagsRaw.split("\\|")));
            int ctCount = parseInt(properties.getProperty("customTag.count"), 0);
            customModelTags = new java.util.LinkedHashMap<>();
            for (int i = 0; i < ctCount; i++) {
                String ctPath = properties.getProperty("customTag." + i + ".path", "");
                String ctTags = properties.getProperty("customTag." + i + ".tags", "");
                if (!ctPath.isBlank() && !ctTags.isBlank()) {
                    customModelTags.put(ctPath, new java.util.LinkedHashSet<>(Arrays.asList(ctTags.split("\\|"))));
                }
            }
            viewerPrefs = new java.util.LinkedHashMap<>();
            uiPrefs = new java.util.LinkedHashMap<>();
            for (String name : properties.stringPropertyNames()) {
                if (name.startsWith("viewerpref.")) {
                    viewerPrefs.put(name.substring("viewerpref.".length()), properties.getProperty(name));
                } else if (name.startsWith("ui.")) {
                    uiPrefs.put(name.substring("ui.".length()), properties.getProperty(name));
                }
            }
        } catch (IOException ignored) {
            // Keep defaults on read failure.
        }
    }

    private static PortraitFilter parsePortraitFilter(String value) {
        try {
            return PortraitFilter.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return PortraitFilter.MODELS_ONLY;
        }
    }

    private static ThumbnailSize parseThumbnailSize(String value) {
        try {
            return ThumbnailSize.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ThumbnailSize.MEDIUM;
        }
    }

    private static ThumbnailQuality parseThumbnailQuality(String value) {
        try {
            return ThumbnailQuality.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ThumbnailQuality.MEDIUM;
        }
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return defaultValue; }
    }

    private static float parseFloat(String value, float defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
