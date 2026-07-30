package com.hiveworkshop.parser;

import com.hiveworkshop.model.ModelAsset;
import com.hiveworkshop.model.ModelMetadata;
import com.hiveworkshop.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ModelScanner {
    private static final Map<Path, List<ModelAsset>> CACHE = new ConcurrentHashMap<>();

    private ModelScanner() {
    }

    public static List<ModelAsset> scan(Path root) throws IOException {
        return scan(root, false, null, null);
    }

    public static List<ModelAsset> scan(Path root, boolean forceRefresh) throws IOException {
        return scan(root, forceRefresh, null, null);
    }

    /**
     * @param progressCallback called with (current, total) as each model is parsed; may be null
     */
    public static List<ModelAsset> scan(Path root, boolean forceRefresh,
                                        BiConsumer<Integer, Integer> progressCallback) throws IOException {
        return scan(root, forceRefresh, progressCallback, null);
    }

    /**
     * @param progressCallback called with (current, total) as each model is parsed; may be null
     * @param cancelled        if non-null, checked periodically — when set to true the scan aborts early
     */
    public static List<ModelAsset> scan(Path root, boolean forceRefresh,
                                        BiConsumer<Integer, Integer> progressCallback,
                                        AtomicBoolean cancelled) throws IOException {
        return scan(root, forceRefresh, progressCallback, cancelled, false);
    }

    /**
     * @param progressCallback called with (current, total) as each model is parsed; may be null
     * @param cancelled        if non-null, checked periodically — when set to true the scan aborts early
     * @param parseTags        if true, look for readme.html in model directories to extract tags
     */
    public static List<ModelAsset> scan(Path root, boolean forceRefresh,
                                        BiConsumer<Integer, Integer> progressCallback,
                                        AtomicBoolean cancelled,
                                        boolean parseTags) throws IOException {
        if (root == null || !Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!forceRefresh) {
            List<ModelAsset> cached = CACHE.get(normalizedRoot);
            if (cached != null) {
                return cached;
            }
        } else {
            ReterasModelParser.clearCache();
        }

        // Phase 1: collect file paths (fast — no parsing)
        List<Path> modelFiles;
        try (Stream<Path> stream = Files.walk(normalizedRoot)) {
            modelFiles = stream
                    .peek(p -> { if (cancelled != null && cancelled.get()) throw new ScanCancelledException(); })
                    .filter(Files::isRegularFile)
                    .filter(ModelScanner::isModelFile)
                    .collect(Collectors.toList());
        } catch (ScanCancelledException e) {
            return List.of();
        }

        if (cancelled != null && cancelled.get()) return List.of();

        int total = modelFiles.size();

        // Phase 2: parse metadata in parallel with progress reporting
        AtomicInteger counter = new AtomicInteger(0);
        List<ModelAsset> results = Collections.synchronizedList(new ArrayList<>(total));
        // Pre-compute tag cache per directory to avoid re-reading readme.html for each model
        Map<Path, List<String>> tagCache = parseTags ? new ConcurrentHashMap<>() : null;

        modelFiles.parallelStream().forEach(path -> {
            if (cancelled != null && cancelled.get()) return;
            try {
                ModelMetadata meta = ModelMetadataExtractor.extract(path);
                List<String> tags = parseTags ? resolveTagsCached(path, tagCache) : List.of();
                results.add(new ModelAsset(path, Files.size(path), meta, null, tags));
            } catch (Exception ex) {
                // Keep the file in results with an error message
                try {
                    String msg = ex.getMessage();
                    if (msg == null || msg.isEmpty()) msg = ex.getClass().getSimpleName();
                    results.add(new ModelAsset(path, Files.size(path), ModelMetadata.EMPTY, msg));
                } catch (Exception ignored) {
                    // File truly unreadable (can't even stat size)
                }
            }
            int done = counter.incrementAndGet();
            if (progressCallback != null) {
                progressCallback.accept(done, total);
            }
        });

        if (cancelled != null && cancelled.get()) return List.copyOf(results);

        results.sort(Comparator.comparing(ModelAsset::fileName, String.CASE_INSENSITIVE_ORDER));
        List<ModelAsset> immutableResults = List.copyOf(results);
        CACHE.put(normalizedRoot, immutableResults);
        return immutableResults;
    }

    /**
     * Resolves tags for a model file, caching results per directory
     * so that readme.html is only read once per folder.
     */
    private static List<String> resolveTagsCached(Path modelFile, Map<Path, List<String>> cache) {
        Path dir = modelFile.getParent();
        if (dir == null) return List.of();
        return cache.computeIfAbsent(dir, d -> ReadmeTagParser.findTags(modelFile));
    }

    /** Unchecked exception used to break out of the Files.walk stream on cancellation. */
    private static final class ScanCancelledException extends RuntimeException {
        ScanCancelledException() { super(null, null, true, false); }
    }

    public static void invalidate(Path root) {
        if (root == null) {
            return;
        }
        CACHE.remove(root.toAbsolutePath().normalize());
    }

    public static void clearCache() {
        CACHE.clear();
        ReterasModelParser.clearCache();
    }

    private static boolean isModelFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mdx") || name.endsWith(".mdl");
    }
}
