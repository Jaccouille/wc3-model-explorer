package com.hiveworkshop.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans a model's directory (and its parent) for a {@code readme.html} file
 * and extracts tags from lines like {@code Tags: Hero, Unit, Historic}.
 */
public final class ReadmeTagParser {

    // Matches "Tags:" (case-insensitive) followed by a comma-separated list
    private static final Pattern TAGS_PATTERN = Pattern.compile(
            "(?i)Tags\\s*:\\s*(.+)");

    private ReadmeTagParser() {}

    /**
     * Finds tags for the given model file by looking for readme.html
     * in the model's directory and its parent directory.
     *
     * @return list of trimmed, non-empty tag strings; empty list if none found
     */
    public static List<String> findTags(Path modelFile) {
        Path dir = modelFile.getParent();
        if (dir == null) return List.of();

        // Check model's own directory first
        List<String> tags = parseReadmeInDir(dir);
        if (!tags.isEmpty()) return tags;

        // Check parent directory
        Path parentDir = dir.getParent();
        if (parentDir != null) {
            tags = parseReadmeInDir(parentDir);
        }
        return tags;
    }

    private static List<String> parseReadmeInDir(Path dir) {
        // Find readme.html (case-insensitive)
        Path readmeFile = findReadmeHtml(dir);
        if (readmeFile == null) return List.of();

        try {
            String content = Files.readString(readmeFile);
            return extractTags(content);
        } catch (IOException e) {
            System.err.println("[ReadmeTagParser] Failed to read " + readmeFile + ": " + e.getMessage());
            return List.of();
        }
    }

    private static Path findReadmeHtml(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals("readme.html"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static List<String> extractTags(String content) {
        // Strip HTML tags to get plain text
        String text = content.replaceAll("<[^>]+>", " ");
        // Decode common HTML entities
        text = text.replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&nbsp;", " ")
                   .replace("&#160;", " ");

        for (String line : text.split("\\r?\\n")) {
            Matcher m = TAGS_PATTERN.matcher(line.trim());
            if (m.find()) {
                String tagString = m.group(1).trim();
                return java.util.Arrays.stream(tagString.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }
        }
        return List.of();
    }
}
