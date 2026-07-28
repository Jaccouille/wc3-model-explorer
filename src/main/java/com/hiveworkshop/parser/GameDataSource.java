package com.hiveworkshop.parser;


import com.hiveworkshop.rms.filesystem.sources.CascDataSource;
import com.hiveworkshop.rms.filesystem.sources.DataSource;
import com.hiveworkshop.rms.filesystem.sources.MpqDataSource;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton that provides texture lookup across:
 *   1. CASC archive (Warcraft III Reforged installation)
 *   2. MPQ archives (legacy .mpq files)
 *
 * Call {@link #refresh(AppSettings)} after the user changes archive paths in Settings.
 * Call {@link #loadTexture(String, Path, Path)} to resolve a texture path to a BufferedImage.
 */
public final class GameDataSource {
    private static final GameDataSource INSTANCE = new GameDataSource();

    private final List<DataSource> sources  = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, BufferedImage> cache = new ConcurrentHashMap<>();
    private volatile DataSource mapSource; // transient source for currently-opened map archive

    private GameDataSource() {}

    public static GameDataSource getInstance() {
        return INSTANCE;
    }

    /** Returns the number of currently loaded data sources (CASC + MPQ archives). */
    public int getSourceCount() {
        return sources.size();
    }

    /** Returns true if at least one data source (CASC or MPQ) is loaded. */
    public boolean hasDataSources() {
        return !sources.isEmpty();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** (Re-)initialise from current settings. Closes any previously open sources. */
    public synchronized void refresh(AppSettings settings) {
        closeSources();
        cache.clear();

        // CASC
        String cascPath = settings.cascPath().trim();
        if (!cascPath.isEmpty()) {
            try {
                DataSource casc = new CascDataSource(cascPath,
                        new String[]{"war3.w3mod", "_hd.w3mod", "_retail_", "_ptr_"});
                sources.add(casc);
                System.out.println("[GameDataSource] Opened CASC: " + cascPath);
            } catch (Exception ex) {
                System.err.println("[GameDataSource] Failed to open CASC '" + cascPath + "': " + ex.getMessage());
            }
        }

        // MPQ archives
        for (String mpqPath : settings.mpqPaths()) {
            if (mpqPath == null || mpqPath.isBlank()) continue;
            try {
                JMpqEditor editor;
                try {
                    editor = new JMpqEditor(Path.of(mpqPath).toFile(), MPQOpenOption.READ_ONLY);
                } catch (Exception ex) {
                    editor = new JMpqEditor(Path.of(mpqPath).toFile(), MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
                    System.out.println("[GameDataSource] Opened MPQ with FORCE_V0: " + mpqPath);
                }
                sources.add(new MpqDataSource(editor));
                System.out.println("[GameDataSource] Opened MPQ: " + mpqPath);
            } catch (Exception ex) {
                System.err.println("[GameDataSource] Failed to open MPQ '" + mpqPath + "': " + ex.getMessage());
            }
        }
    }

    public synchronized void close() {
        clearMapSource();
        closeSources();
        cache.clear();
    }

    /** Sets a transient map archive source (checked before CASC/MPQ). */
    public synchronized void setMapSource(DataSource source) {
        clearMapSource();
        mapSource = source;
        cache.clear();
    }

    /** Removes the transient map archive source. */
    public synchronized void clearMapSource() {
        if (mapSource != null) {
            try { mapSource.close(); } catch (Exception ignored) {}
            mapSource = null;
            cache.clear();
        }
    }

    private void closeSources() {
        for (DataSource src : sources) {
            try { src.close(); } catch (Exception ignored) {}
        }
        sources.clear();
    }

    // ── Texture resolution ────────────────────────────────────────────────────

    /**
     * Resolves a texture path (e.g. "Textures\Grunt.blp") to a BufferedImage.
     * Resolution order:
     *   1. Relative to the model's own directory
     *   2. Relative to the scan root directory
     *   3. CASC archive
     *   4. MPQ archives
     *
     * Returns {@code null} if the texture cannot be found anywhere.
     *
     * @param texturePath  raw path from the MDX/MDL file (may use backslashes)
     * @param modelDir     directory that contains the model file (may be null)
     * @param rootDir      scan root directory chosen by the user (may be null)
     */
    public BufferedImage loadTexture(String texturePath, Path modelDir, Path rootDir) {
        if (texturePath == null || texturePath.isBlank()) return null;
        String key = texturePath.toLowerCase(Locale.ROOT);

        BufferedImage cached = cache.get(key);
        if (cached != null) return cached;

        // Normalise separator: MDX files use backslashes
        String normalised = texturePath.replace('\\', '/');

        // Build extension variants: original, then .dds fallback
        String[] variants = extensionVariants(normalised);

        BufferedImage img = null;
        for (String variant : variants) {
            // Map source first (custom textures in map override everything)
            DataSource ms = mapSource;
            if (ms != null) {
                img = tryLoadFromSource(ms, variant);
                if (img != null) break;
            }
            // 1 + 2 – filesystem lookups
            img = loadFromDisk(variant, modelDir, rootDir);
            // 3+4 – archive sources (CASC, MPQ)
            if (img == null) {
                img = loadFromSources(variant);
            }
            if (img != null) break;
        }

        if (img != null) {
            cache.put(key, img);
        }
        return img;
    }

    /** Returns true if any archive source is open. */
    public boolean hasArchive() {
        return mapSource != null || !sources.isEmpty();
    }

    /**
     * Extracts a game-relative path (e.g. "units\\human\\footman\\footman.mdx") from
     * the archive sources to a temporary file on disk. Returns the temp file Path,
     * or null if not found. The caller is responsible for cleanup.
     */
    public Path extractToTemp(String gamePath) {
        if (gamePath == null || gamePath.isBlank()) return null;
        String normalised = gamePath.replace('\\', '/');
        String backslash = normalised.replace('/', '\\');
        String lower = normalised.toLowerCase(Locale.ROOT);
        String lowerBackslash = lower.replace('/', '\\');

        // Build list of sources to check: map source first, then regular sources
        List<DataSource> allSources = new ArrayList<>();
        DataSource ms = mapSource;
        if (ms != null) allSources.add(ms);
        allSources.addAll(sources);

        for (DataSource src : allSources) {
            for (String variant : new String[]{normalised, backslash, lower, lowerBackslash}) {
                if (!src.has(variant)) continue;
                try (InputStream is = src.getResourceAsStream(variant)) {
                    if (is == null) continue;
                    String ext = normalised.contains(".") ? normalised.substring(normalised.lastIndexOf('.')) : ".tmp";
                    Path tmp = Files.createTempFile("wc3_preview_", ext);
                    Files.write(tmp, is.readAllBytes());
                    return tmp;
                } catch (Exception ex) {
                    System.err.println("[GameDataSource] Failed to extract '" + variant + "': " + ex.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * Extracts a game file preferring the HD (_hd.w3mod) CASC module.
     * Opens a temporary CASC source with HD-first prefix order, extracts, then closes it.
     * Falls back to {@link #extractToTemp(String)} if no CASC path is configured or HD not found.
     */
    public Path extractToTempHd(String gamePath) {
        String cascPath = AppSettings.loadDefault().cascPath().trim();
        if (!cascPath.isEmpty()) {
            CascDataSource hdCasc = null;
            try {
                hdCasc = new CascDataSource(cascPath,
                        new String[]{"_hd.w3mod", "war3.w3mod", "_retail_", "_ptr_"});
                String normalised = gamePath.replace('\\', '/');
                String backslash = normalised.replace('/', '\\');
                String lower = normalised.toLowerCase(Locale.ROOT);
                String lowerBackslash = lower.replace('/', '\\');
                for (String variant : new String[]{normalised, backslash, lower, lowerBackslash}) {
                    if (!hdCasc.has(variant)) continue;
                    try (InputStream is = hdCasc.getResourceAsStream(variant)) {
                        if (is == null) continue;
                        String ext = normalised.contains(".") ? normalised.substring(normalised.lastIndexOf('.')) : ".tmp";
                        Path tmp = Files.createTempFile("wc3_preview_hd_", ext);
                        Files.write(tmp, is.readAllBytes());
                        return tmp;
                    }
                }
            } catch (Exception ex) {
                System.err.println("[GameDataSource] Failed HD extraction for '" + gamePath + "': " + ex.getMessage());
            } finally {
                if (hdCasc != null) try { hdCasc.close(); } catch (Exception ignored) {}
            }
        }
        return extractToTemp(gamePath);
    }

    public BufferedImage loadTeamColorTexture(int teamColorIdx, Path modelDir, Path rootDir) {
        return loadTexture(teamColorPath(teamColorIdx), modelDir, rootDir);
    }

    public int[] loadTeamColorRgb(int teamColorIdx, Path modelDir, Path rootDir) {
        BufferedImage img = loadTeamColorTexture(teamColorIdx, modelDir, rootDir);
        if (img == null) {
            return null;
        }

        long r = 0, g = 0, b = 0, count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                r += (argb >>> 16) & 0xFF;
                g += (argb >>> 8) & 0xFF;
                b += argb & 0xFF;
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        return new int[]{
                (int) Math.round((double) r / count),
                (int) Math.round((double) g / count),
                (int) Math.round((double) b / count)
        };
    }

    /**
     * Determines where a texture can be found without loading it.
     * @return "DISK", "CASC", "MPQ", "REPLACEABLE", or "MISSING"
     */
    public String resolveTextureSource(String texturePath, int replaceableId,
                                        Path modelDir, Path rootDir) {
        if (replaceableId == 1 || replaceableId == 2) return "REPLACEABLE";
        if (texturePath == null || texturePath.isBlank()) return "MISSING";

        String normalised = texturePath.replace('\\', '/');
        String[] variants = extensionVariants(normalised);

        // Map source takes priority
        DataSource ms = mapSource;
        if (ms != null) {
            for (String variant : variants) {
                String bs = variant.replace('/', '\\');
                String lo = variant.toLowerCase(Locale.ROOT);
                String lbs = lo.replace('/', '\\');
                if (ms.has(variant) || ms.has(bs) || ms.has(lo) || ms.has(lbs)) return "MAP";
            }
        }
        for (String variant : variants) {
            if (existsOnDisk(variant, modelDir, rootDir)) return "DISK";
        }
        for (String variant : variants) {
            if (existsInSources(variant)) {
                return findSourceType(variant);
            }
        }
        return "MISSING";
    }

    /**
     * Returns the on-disk file backing the given texture reference, or {@code null}
     * if the texture is not found as a loose file (e.g. it lives inside a CASC/MPQ
     * archive, is replaceable, or is missing). Used by "Copy with textures" to gather
     * only the custom textures shipped alongside a model.
     */
    public Path resolveDiskFile(String texturePath, Path modelDir, Path rootDir) {
        if (texturePath == null || texturePath.isBlank()) return null;
        String normalised = texturePath.replace('\\', '/');
        for (String variant : extensionVariants(normalised)) {
            Path found = diskFileAt(variant, modelDir, rootDir);
            if (found != null) return found;
        }
        return null;
    }

    private Path diskFileAt(String normalised, Path modelDir, Path rootDir) {
        if (modelDir != null) {
            int maxDepth = 10;
            for (Path dir = modelDir; dir != null && maxDepth-- > 0; dir = dir.getParent()) {
                Path f = regularFileAt(dir, normalised);
                if (f != null) return f;
            }
        }
        if (rootDir != null) {
            Path f = regularFileAt(rootDir, normalised);
            if (f != null) return f;
        }
        return null;
    }

    private static Path regularFileAt(Path dir, String normalised) {
        try {
            Path full = dir.resolve(normalised);
            if (Files.isRegularFile(full)) return full;
            int slash = normalised.lastIndexOf('/');
            if (slash >= 0) {
                Path byName = dir.resolve(normalised.substring(slash + 1));
                if (Files.isRegularFile(byName)) return byName;
            }
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
        return null;
    }

    private boolean existsOnDisk(String normalised, Path modelDir, Path rootDir) {
        if (modelDir != null) {
            int maxDepth = 10;
            for (Path dir = modelDir; dir != null && maxDepth-- > 0; dir = dir.getParent()) {
                if (existsAt(dir, normalised)) return true;
            }
        }
        if (rootDir != null && existsAt(rootDir, normalised)) return true;
        return false;
    }

    private static boolean existsAt(Path dir, String normalised) {
        try {
            if (Files.isRegularFile(dir.resolve(normalised))) return true;
            int slash = normalised.lastIndexOf('/');
            return slash >= 0 && Files.isRegularFile(dir.resolve(normalised.substring(slash + 1)));
        } catch (java.nio.file.InvalidPathException e) {
            return false; // CASC virtual paths may contain ':' which is illegal on Windows
        }
    }

    private static String teamColorPath(int teamColorIdx) {
        int idx = Math.max(0, teamColorIdx);
        return String.format(Locale.ROOT, "ReplaceableTextures\\TeamColor\\TeamColor%02d.blp", idx);
    }

    private boolean existsInSources(String normalised) {
        String backslash      = normalised.replace('/', '\\');
        String lower          = normalised.toLowerCase(Locale.ROOT);
        String lowerBackslash = lower.replace('/', '\\');
        DataSource ms = mapSource;
        if (ms != null && (ms.has(normalised) || ms.has(backslash)
                || ms.has(lower) || ms.has(lowerBackslash))) return true;
        for (DataSource src : sources) {
            if (src.has(normalised) || src.has(backslash)
                    || src.has(lower) || src.has(lowerBackslash)) return true;
        }
        return false;
    }

    private String findSourceType(String normalised) {
        String backslash      = normalised.replace('/', '\\');
        String lower          = normalised.toLowerCase(Locale.ROOT);
        String lowerBackslash = lower.replace('/', '\\');
        DataSource ms = mapSource;
        if (ms != null && (ms.has(normalised) || ms.has(backslash)
                || ms.has(lower) || ms.has(lowerBackslash))) return "MAP";
        for (DataSource src : sources) {
            if (src.has(normalised) || src.has(backslash)
                    || src.has(lower) || src.has(lowerBackslash)) {
                if (src instanceof CascDataSource) return "CASC";
                if (src instanceof MpqDataSource) return "MPQ";
                return "ARCHIVE";
            }
        }
        return "MISSING";
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private BufferedImage loadFromDisk(String normalised, Path modelDir, Path rootDir) {
        // Try modelDir, then walk up ancestors from modelDir (up to rootDir or filesystem root).
        // WC3 extracted archives have textures at the archive root (e.g. war3.w3mod/Textures/...)
        // while models live deeper (e.g. war3.w3mod/units/human/footman/).
        if (modelDir != null) {
            // Walk up to 10 levels (modelDir → ancestors), stop at filesystem root
            int maxDepth = 10;
            for (Path dir = modelDir; dir != null && maxDepth-- > 0; dir = dir.getParent()) {
                BufferedImage img = tryDiskResolve(dir, normalised);
                if (img != null) return img;
            }
        }
        if (rootDir != null) {
            BufferedImage img = tryDiskResolve(rootDir, normalised);
            if (img != null) return img;
        }
        return null;
    }

    private BufferedImage tryDiskResolve(Path dir, String normalised) {
        try {
            Path candidate = dir.resolve(normalised);
            if (Files.isRegularFile(candidate)) {
                System.out.println("[GameDataSource] Found on disk: " + candidate);
                return readImage(candidate);
            }
            // Also try bare filename (without leading path components)
            int slash = normalised.lastIndexOf('/');
            if (slash >= 0) {
                candidate = dir.resolve(normalised.substring(slash + 1));
                if (Files.isRegularFile(candidate)) {
                    System.out.println("[GameDataSource] Found on disk: " + candidate);
                    return readImage(candidate);
                }
            }
        } catch (java.nio.file.InvalidPathException e) {
            // CASC virtual paths may contain ':' which is illegal on Windows
        }
        return null;
    }

    private static BufferedImage readImage(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            // Use InputStream so ImageIO SPI (blp-iio-plugin) can handle BLP files
            BufferedImage img;
            try (InputStream is = Files.newInputStream(path)) {
                img = ImageIO.read(is);
            }
            if (img != null) {
                System.out.println("[GameDataSource] Read OK: " + path);
            } else {
                System.err.println("[GameDataSource] ImageIO returned null for: " + path);
            }
            return img;
        } catch (Exception ex) {
            System.err.println("[GameDataSource] Failed to read '" + path + "': " + ex.getMessage());
            return null;
        }
    }

    private BufferedImage loadFromSources(String normalised) {
        // Build path variants: original, backslash, lowercase, lowercase+backslash.
        // CASC on Reforged 2.x stores paths in lowercase; some BLP files only match when
        // the lookup key is fully lowercased.
        String backslash      = normalised.replace('/', '\\');
        String lower          = normalised.toLowerCase(Locale.ROOT);
        String lowerBackslash = lower.replace('/', '\\');

        // Check map source first (highest priority for map-embedded textures)
        DataSource ms = mapSource;
        if (ms != null) {
            BufferedImage img = tryLoad(ms, normalised);
            if (img == null) img = tryLoad(ms, backslash);
            if (img == null) img = tryLoad(ms, lower);
            if (img == null) img = tryLoad(ms, lowerBackslash);
            if (img != null) return img;
        }

        for (DataSource src : sources) {
            BufferedImage img = tryLoad(src, normalised);
            if (img == null) img = tryLoad(src, backslash);
            if (img == null) img = tryLoad(src, lower);
            if (img == null) img = tryLoad(src, lowerBackslash);
            if (img != null) return img;
        }
        return null;
    }

    /** Returns the original path plus extension variants for texture resolution.
     *  Handles .blp↔.dds and .tif→.dds (Reforged HD models reference .tif but CASC stores .dds).
     *  When only CASC is loaded (no MPQ), .dds is preferred over .blp since CASC stores DDS textures. */
    private String[] extensionVariants(String normalised) {
        boolean cascOnly = isCascOnly();
        String lower = normalised.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".blp")) {
            String ddsVariant = normalised.substring(0, normalised.length() - 4) + ".dds";
            return cascOnly ? new String[]{ddsVariant, normalised}
                            : new String[]{normalised, ddsVariant};
        }
        if (lower.endsWith(".dds")) {
            String blpVariant = normalised.substring(0, normalised.length() - 4) + ".blp";
            return cascOnly ? new String[]{normalised, blpVariant}
                            : new String[]{blpVariant, normalised};
        }
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
            int dotIdx = normalised.lastIndexOf('.');
            String base = normalised.substring(0, dotIdx);
            return new String[]{base + ".dds", base + ".blp", normalised};
        }
        // No extension or other format — try as-is, then preferred format first
        return cascOnly ? new String[]{normalised, normalised + ".dds", normalised + ".blp"}
                        : new String[]{normalised, normalised + ".blp", normalised + ".dds"};
    }

    /** True if the only archive sources are CASC (no MPQ). */
    private boolean isCascOnly() {
        boolean hasCasc = false;
        for (DataSource src : sources) {
            if (src instanceof MpqDataSource) return false;
            if (src instanceof CascDataSource) hasCasc = true;
        }
        return hasCasc;
    }

    private static BufferedImage tryLoadFromSource(DataSource src, String normalised) {
        BufferedImage img = tryLoad(src, normalised);
        if (img == null) img = tryLoad(src, normalised.replace('/', '\\'));
        if (img == null) img = tryLoad(src, normalised.toLowerCase(Locale.ROOT));
        if (img == null) img = tryLoad(src, normalised.toLowerCase(Locale.ROOT).replace('/', '\\'));
        return img;
    }

    private static BufferedImage tryLoad(DataSource src, String path) {
        if (!src.has(path)) return null;
        try (InputStream is = src.getResourceAsStream(path)) {
            if (is == null) return null;
            return ImageIO.read(is);  // blp-iio-plugin registers a BLP ImageReader via SPI
        } catch (Exception ex) {
            System.err.println("[GameDataSource] Failed to decode '" + path + "': " + ex.getMessage());
            return null;
        }
    }
}
