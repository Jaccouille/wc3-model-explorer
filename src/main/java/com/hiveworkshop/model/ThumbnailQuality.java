package com.hiveworkshop.model;

import com.hiveworkshop.i18n.Messages;

/** Thumbnail rendering quality presets. */
public enum ThumbnailQuality {
    LOW("quality.low", 256, 256),
    MEDIUM("quality.medium", 512, 256),
    HIGH("quality.high", 1024, 256);

    private final String key;
    private final int renderSize;
    private final int thumbSize;

    ThumbnailQuality(String key, int renderSize, int thumbSize) {
        this.key = key;
        this.renderSize = renderSize;
        this.thumbSize = thumbSize;
    }

    public int renderSize() { return renderSize; }
    public int thumbSize() { return thumbSize; }

    @Override
    public String toString() { return Messages.get(key); }
}
