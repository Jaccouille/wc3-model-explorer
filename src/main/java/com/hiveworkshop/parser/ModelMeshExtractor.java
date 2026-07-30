package com.hiveworkshop.parser;

import com.hiveworkshop.model.ModelAsset;
import com.hiveworkshop.model.ModelMesh;
import com.hiveworkshop.model.*;

public final class ModelMeshExtractor {
    private ModelMeshExtractor() {
    }

    public static ModelMesh extract(ModelAsset asset) {
        if (asset == null) {
            return ModelMesh.EMPTY;
        }
        return ReterasModelParser.parse(asset.path()).mesh();
    }
}
