package com.war3.viewer.app.datasource;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collection;

public interface DataSource extends Closeable {
    InputStream getResourceAsStream(String filepath) throws IOException;
    ByteBuffer read(String path) throws IOException;
    File getFile(String filepath) throws IOException;
    boolean has(String filepath);
    boolean allowDownstreamCaching(String filepath);
    Collection<String> getListfile();
}
