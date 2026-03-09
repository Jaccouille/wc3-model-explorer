package com.war3.viewer.app.datasource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CompoundDataSource implements DataSource {
    private final List<DataSource> sources = new ArrayList<>();
    private final Map<String, File> cache = new HashMap<>();

    public CompoundDataSource(final List<DataSource> dataSources) {
        if (dataSources != null) {
            sources.addAll(dataSources);
        }
    }

    @Override
    public InputStream getResourceAsStream(final String filepath) {
        try {
            for (int i = sources.size() - 1; i >= 0; i--) {
                final InputStream stream = sources.get(i).getResourceAsStream(filepath);
                if (stream != null) return stream;
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public ByteBuffer read(final String path) throws IOException {
        for (int i = sources.size() - 1; i >= 0; i--) {
            final ByteBuffer buf = sources.get(i).read(path);
            if (buf != null) return buf;
        }
        return null;
    }

    @Override
    public File getFile(final String filepath) {
        if (cache.containsKey(filepath)) {
            return cache.get(filepath);
        }
        try {
            for (int i = sources.size() - 1; i >= 0; i--) {
                final File f = sources.get(i).getFile(filepath);
                if (f != null) {
                    cache.put(filepath, f);
                    return f;
                }
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean has(final String filepath) {
        if (cache.containsKey(filepath)) return true;
        for (int i = sources.size() - 1; i >= 0; i--) {
            if (sources.get(i).has(filepath)) return true;
        }
        return false;
    }

    @Override
    public boolean allowDownstreamCaching(final String filepath) {
        for (int i = sources.size() - 1; i >= 0; i--) {
            final DataSource src = sources.get(i);
            if (src.has(filepath)) return src.allowDownstreamCaching(filepath);
        }
        return false;
    }

    @Override
    public Collection<String> getListfile() {
        final Set<String> merged = new HashSet<>();
        for (final DataSource src : sources) {
            final Collection<String> lf = src.getListfile();
            if (lf != null) merged.addAll(lf);
        }
        return merged;
    }

    @Override
    public void close() throws IOException {
        for (final DataSource src : sources) {
            try { src.close(); } catch (final IOException e) { e.printStackTrace(); }
        }
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }
}
