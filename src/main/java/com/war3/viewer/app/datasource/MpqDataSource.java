package com.war3.viewer.app.datasource;

import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MpqFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collection;

public final class MpqDataSource implements DataSource {
    private final JMpqEditor archive;

    public MpqDataSource(final JMpqEditor archive) {
        this.archive = archive;
    }

    @Override
    public InputStream getResourceAsStream(final String filepath) throws IOException {
        final MpqFile file = getMpqFile(filepath);
        if (file == null) return null;
        return new ByteArrayInputStream(file.extractToBytes());
    }

    @Override
    public ByteBuffer read(final String path) throws IOException {
        final MpqFile file = getMpqFile(path);
        if (file != null) {
            return ByteBuffer.wrap(file.extractToBytes());
        }
        return null;
    }

    @Override
    public File getFile(final String filepath) throws IOException {
        final MpqFile file = getMpqFile(filepath);
        if (file == null) return null;

        String tmpdir = System.getProperty("java.io.tmpdir");
        if (!tmpdir.endsWith(File.separator)) tmpdir += File.separator;
        final File tempProduct = new File(tmpdir + "Wc3ViewerMpq/" + filepath.replace('\\', File.separatorChar));
        tempProduct.delete();
        tempProduct.getParentFile().mkdirs();
        file.extractToFile(tempProduct);
        tempProduct.deleteOnExit();
        return tempProduct;
    }

    private MpqFile getMpqFile(final String filepath) throws IOException {
        try {
            if (archive.hasFile(filepath)) {
                return archive.getMpqFile(filepath);
            }
        } catch (final Exception exc) {
            if (exc.getMessage() != null && exc.getMessage().startsWith("File Not Found")) {
                return null;
            }
            throw new IOException(exc);
        }
        return null;
    }

    @Override
    public boolean has(final String filepath) {
        return archive.hasFile(filepath);
    }

    @Override
    public boolean allowDownstreamCaching(final String filepath) {
        return true;
    }

    @Override
    public Collection<String> getListfile() {
        return archive.getFileNames();
    }

    @Override
    public void close() throws IOException {
        archive.close();
    }
}
