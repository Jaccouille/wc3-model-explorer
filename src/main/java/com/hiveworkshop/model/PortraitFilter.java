package com.hiveworkshop.model;

import com.hiveworkshop.i18n.Messages;

import java.util.Locale;

public enum PortraitFilter {
    MODELS_ONLY("portrait.modelsOnly"),
    PORTRAITS_ONLY("portrait.portraitsOnly"),
    BOTH("portrait.both");

    private final String key;

    PortraitFilter(String key) {
        this.key = key;
    }

    public boolean allows(ModelAsset asset) {
        boolean portrait = isPortrait(asset);
        return switch (this) {
            case MODELS_ONLY -> !portrait;
            case PORTRAITS_ONLY -> portrait;
            case BOTH -> true;
        };
    }

    @Override
    public String toString() {
        return Messages.get(key);
    }

    private static boolean isPortrait(ModelAsset asset) {
        String name = asset.fileName().toLowerCase(Locale.ROOT);
        return name.endsWith("_portrait.mdx") || name.endsWith("_portrait.mdl");
    }
}
