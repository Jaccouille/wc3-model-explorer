package com.hiveworkshop.parser;

import com.hiveworkshop.rms.filesystem.sources.MpqDataSource;
import systems.crigges.jmpq3.BlockTable;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;
import systems.crigges.jmpq3.MpqFile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

/**
 * Wraps a Warcraft III map file (.w3x/.w3m) as a model scan source.
 * Extracts all .mdx/.mdl files to a temp directory so the existing
 * ModelScanner can process them. Also exposes the map's MPQ as a
 * DataSource for texture resolution.
 */
public final class MapArchiveSource implements AutoCloseable {
    private final Path mapFile;
    private final JMpqEditor editor;
    private final MpqDataSource dataSource;
    private final Path tempDir;

    private MapArchiveSource(Path mapFile, JMpqEditor editor, MpqDataSource dataSource, Path tempDir) {
        this.mapFile = mapFile;
        this.editor = editor;
        this.dataSource = dataSource;
        this.tempDir = tempDir;
    }

    /**
     * Opens a .w3x/.w3m map, extracts all .mdx/.mdl files to a temp directory.
     *
     * @param mapFile path to the map archive
     * @return a new MapArchiveSource, ready for scanning
     * @throws IOException if the map cannot be opened
     */
    public static MapArchiveSource open(Path mapFile) throws IOException {
        JMpqEditor editor;
        try {
            editor = new JMpqEditor(mapFile.toFile(), MPQOpenOption.READ_ONLY);
        } catch (Exception ex) {
            // Retry with FORCE_V0 for maps with non-standard headers
            try {
                editor = new JMpqEditor(mapFile.toFile(), MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
                System.out.println("[MapArchiveSource] Opened with FORCE_V0: " + mapFile.getFileName());
            } catch (Exception ex2) {
                throw new IOException("Failed to open map archive: " + ex.getMessage(), ex);
            }
        }

        MpqDataSource mpqSource = new MpqDataSource(editor);
        Path tempDir = Files.createTempDirectory("wc3map_");

        // Try listfile-based extraction first
        Collection<String> fileNames = mpqSource.getListfile();
        int modelCount = 0;
        if (fileNames != null && !fileNames.isEmpty()) {
            for (String name : fileNames) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".mdx") || lower.endsWith(".mdl")) {
                    if (extractNamedFile(mpqSource, name, tempDir)) modelCount++;
                }
            }
        }

        // If listfile yielded no models, brute-force scan the block table
        if (modelCount == 0) {
            System.out.println("[MapArchiveSource] No models in listfile, scanning block table...");
            modelCount = extractModelsFromBlockTable(editor, tempDir);
        }

        int totalFiles = fileNames != null ? fileNames.size() : 0;
        System.out.println("[MapArchiveSource] Opened map: " + mapFile.getFileName()
                + " (" + modelCount + " models found"
                + (totalFiles > 0 ? ", " + totalFiles + " listed files" : ", protected map") + ")");
        return new MapArchiveSource(mapFile, editor, mpqSource, tempDir);
    }

    private static boolean extractNamedFile(MpqDataSource source, String name, Path tempDir) {
        try (InputStream is = source.getResourceAsStream(name)) {
            if (is == null) return false;
            String normalised = name.replace('\\', '/');
            Path outPath = tempDir.resolve(normalised);
            Files.createDirectories(outPath.getParent());
            Files.write(outPath, is.readAllBytes());
            return true;
        } catch (Exception ex) {
            System.err.println("[MapArchiveSource] Failed to extract '" + name + "': " + ex.getMessage());
            return false;
        }
    }

    /**
     * Scans the MPQ block table directly and extracts files whose raw bytes
     * start with known magic headers (MDLX for models, BLP for textures).
     * This works even on protected maps with stripped listfiles.
     */
    private static int extractModelsFromBlockTable(JMpqEditor editor, Path tempDir) {
        try {
            // Access internal fields via reflection
            Field blockTableField = JMpqEditor.class.getDeclaredField("blockTable");
            blockTableField.setAccessible(true);
            BlockTable blockTable = (BlockTable) blockTableField.get(editor);

            Field fcField = JMpqEditor.class.getDeclaredField("fc");
            fcField.setAccessible(true);
            FileChannel fc = (FileChannel) fcField.get(editor);

            Field headerOffsetField = JMpqEditor.class.getDeclaredField("headerOffset");
            headerOffsetField.setAccessible(true);
            long headerOffset = headerOffsetField.getLong(editor);

            Field discBlockSizeField = JMpqEditor.class.getDeclaredField("discBlockSize");
            discBlockSizeField.setAccessible(true);
            int discBlockSize = discBlockSizeField.getInt(editor);

            ArrayList<BlockTable.Block> blocks = blockTable.getAllVaildBlocks();
            int modelIdx = 0;
            int textureIdx = 0;

            for (int i = 0; i < blocks.size(); i++) {
                BlockTable.Block block = blocks.get(i);
                // Skip deleted/empty blocks
                if (block.getNormalSize() <= 0 || block.getCompressedSize() <= 0) continue;
                if (!block.hasFlag(MpqFile.EXISTS)) continue;

                try {
                    // Read the raw file data from the channel
                    long filePos = headerOffset + block.getFilePos();
                    int readSize = block.getCompressedSize();
                    ByteBuffer buf = ByteBuffer.allocate(readSize);
                    fc.read(buf, filePos);
                    buf.flip();

                    // Create MpqFile to handle decompression
                    String fakeName = "block_" + i;
                    MpqFile mpqFile = new MpqFile(buf, block, discBlockSize, fakeName);
                    byte[] data = mpqFile.extractToBytes();

                    if (data.length < 4) continue;

                    // Check magic header
                    String magic = new String(data, 0, 4);
                    if ("MDLX".equals(magic)) {
                        String name = "block_" + i + ".mdx";
                        Path outPath = tempDir.resolve(name);
                        Files.write(outPath, data);
                        modelIdx++;
                        System.out.println("[MapArchiveSource] Found model in block " + i
                                + " (" + data.length + " bytes)");
                    } else if (data.length >= 4 && data[0] == 'B' && data[1] == 'L' && data[2] == 'P') {
                        // BLP texture — extract for texture resolution
                        String name = "block_" + i + ".blp";
                        Path outPath = tempDir.resolve(name);
                        Files.write(outPath, data);
                        textureIdx++;
                    }
                } catch (Exception ex) {
                    // Decompression failures are expected for non-file blocks
                }
            }

            if (textureIdx > 0) {
                System.out.println("[MapArchiveSource] Also extracted " + textureIdx + " textures from block table");
            }
            return modelIdx;
        } catch (Exception ex) {
            System.err.println("[MapArchiveSource] Block table scan failed: " + ex.getMessage());
            return 0;
        }
    }

    /** The temp directory containing extracted .mdx/.mdl files, ready for ModelScanner. */
    public Path getTempDir() { return tempDir; }

    /** The map's MPQ wrapped as a DataSource for texture resolution. */
    public MpqDataSource getDataSource() { return dataSource; }

    /** The original map file path. */
    public Path getMapFile() { return mapFile; }

    public static boolean isMapFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".w3x") || name.endsWith(".w3m");
    }

    @Override
    public void close() {
        // Delete temp directory
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
        // Close MPQ editor
        try { editor.close(); } catch (Exception ignored) {}
        System.out.println("[MapArchiveSource] Closed map: " + mapFile.getFileName());
    }
}
