package com.hiveworkshop.parser;

import com.hiveworkshop.model.ModelMetadata;

import java.nio.file.Path;

public final class ModelMetadataExtractor {
    private ModelMetadataExtractor() {
    }

    public static ModelMetadata extract(Path path) {
        if (path == null) {
            return ModelMetadata.EMPTY;
        }
        return ReterasModelParser.parse(path).metadata();
    }
}
