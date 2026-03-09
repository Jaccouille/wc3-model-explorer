package com.war3.viewer.app.datasource;

import com.hiveworkshop.blizzard.casc.io.WC3CascFileSystem;
import com.hiveworkshop.blizzard.casc.io.WarcraftIIICASC;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CascDataSource implements DataSource {
    private final String[] prefixes;
    private final WarcraftIIICASC warcraftIIICASC;
    private final WC3CascFileSystem rootFileSystem;
    private final List<String> listFile;
    private final Map<String, String> fileAliases;

    public CascDataSource(final String warcraft3InstallPath, final String[] prefixes) throws IOException {
        this.prefixes = prefixes.clone();
        // Reverse prefix order so highest-priority prefix is checked last → first (iterate in reverse)
        for (int i = 0; i < (this.prefixes.length / 2); i++) {
            final String temp = this.prefixes[i];
            this.prefixes[i] = this.prefixes[this.prefixes.length - i - 1];
            this.prefixes[this.prefixes.length - i - 1] = temp;
        }

        warcraftIIICASC = new WarcraftIIICASC(Paths.get(warcraft3InstallPath), true);
        rootFileSystem = warcraftIIICASC.getRootFileSystem();
        listFile = rootFileSystem.enumerateFiles();
        fileAliases = new HashMap<>();

        if (has("filealiases.json")) {
            try (InputStream stream = getResourceAsStream("filealiases.json")) {
                stream.mark(4);
                if ('\ufeff' != stream.read()) {
                    stream.reset();
                }
                final JSONArray jsonArray = new JSONArray(new JSONTokener(stream));
                for (int i = 0; i < jsonArray.length(); i++) {
                    final JSONObject alias = jsonArray.getJSONObject(i);
                    final String src = alias.getString("src").toLowerCase(Locale.US).replace('/', '\\');
                    final String dest = alias.getString("dest").toLowerCase(Locale.US).replace('/', '\\');
                    fileAliases.put(src, dest);
                    // Also map .blp ↔ .dds variants for texture lookups
                    if ((src.contains(".blp") || dest.contains(".blp"))
                            && (!alias.has("assetType") || "Texture".equals(alias.getString("assetType")))) {
                        fileAliases.put(src.replace(".blp", ".dds"), dest.replace(".blp", ".dds"));
                    }
                }
            }
        }
    }

    @Override
    public InputStream getResourceAsStream(final String filepath) {
        String fp = formatted(filepath);
        final String resolved = fileAliases.get(fp);
        if (resolved != null) fp = resolved;

        for (final String prefix : prefixes) {
            final InputStream stream = internalStream(prefix + "\\" + fp);
            if (stream != null) return stream;
        }
        return internalStream(fp);
    }

    private InputStream internalStream(final String path) {
        try {
            if (rootFileSystem.isFile(path) && rootFileSystem.isFileAvailable(path)) {
                final ByteBuffer buffer = rootFileSystem.readFileData(path);
                if (buffer.hasArray()) {
                    return new ByteArrayInputStream(buffer.array());
                }
                final byte[] data = new byte[buffer.remaining()];
                buffer.clear();
                buffer.get(data);
                return new ByteArrayInputStream(data);
            }
        } catch (final IOException e) {
            throw new RuntimeException("CASC read error for: " + path, e);
        }
        return null;
    }

    @Override
    public ByteBuffer read(String path) {
        path = formatted(path);
        final String resolved = fileAliases.get(path);
        if (resolved != null) path = resolved;
        for (final String prefix : prefixes) {
            final ByteBuffer buf = internalRead(prefix + "\\" + path);
            if (buf != null) return buf;
        }
        return internalRead(path);
    }

    private ByteBuffer internalRead(final String path) {
        try {
            if (rootFileSystem.isFile(path) && rootFileSystem.isFileAvailable(path)) {
                return rootFileSystem.readFileData(path);
            }
        } catch (final IOException e) {
            throw new RuntimeException("CASC read error for: " + path, e);
        }
        return null;
    }

    @Override
    public File getFile(String filepath) {
        filepath = formatted(filepath);
        final String resolved = fileAliases.get(filepath);
        if (resolved != null) filepath = resolved;
        for (final String prefix : prefixes) {
            final File f = internalFile(prefix + "\\" + filepath);
            if (f != null) return f;
        }
        return internalFile(filepath);
    }

    private File internalFile(final String path) {
        try {
            if (rootFileSystem.isFile(path) && rootFileSystem.isFileAvailable(path)) {
                final ByteBuffer buffer = rootFileSystem.readFileData(path);
                String tmpdir = System.getProperty("java.io.tmpdir");
                if (!tmpdir.endsWith(File.separator)) tmpdir += File.separator;
                final File tempProduct = new File(tmpdir + "Wc3ViewerExtract/" + path.replace('\\', File.separatorChar));
                tempProduct.delete();
                tempProduct.getParentFile().mkdirs();
                try (FileChannel fc = FileChannel.open(tempProduct.toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING)) {
                    fc.write(buffer);
                }
                tempProduct.deleteOnExit();
                return tempProduct;
            }
        } catch (final IOException e) {
            throw new RuntimeException("CASC extract error for: " + path, e);
        }
        return null;
    }

    @Override
    public boolean has(String filepath) {
        filepath = formatted(filepath);
        final String resolved = fileAliases.get(filepath);
        if (resolved != null) filepath = resolved;
        for (final String prefix : prefixes) {
            try {
                if (rootFileSystem.isFile(prefix + "\\" + filepath)) return true;
            } catch (final IOException e) {
                throw new RuntimeException("CASC check error for: " + filepath, e);
            }
        }
        try {
            return rootFileSystem.isFile(filepath);
        } catch (final IOException e) {
            throw new RuntimeException("CASC check error for: " + filepath, e);
        }
    }

    private String formatted(final String filepath) {
        return filepath.toLowerCase(Locale.US).replace('/', '\\').replace(':', '\\');
    }

    @Override
    public boolean allowDownstreamCaching(final String filepath) {
        return true;
    }

    @Override
    public Collection<String> getListfile() {
        return listFile;
    }

    @Override
    public void close() throws IOException {
        warcraftIIICASC.close();
    }
}
