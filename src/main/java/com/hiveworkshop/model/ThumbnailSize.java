package com.hiveworkshop.model;

import com.hiveworkshop.i18n.Messages;

public enum ThumbnailSize {
    SMALL("thumbnail.small", 128),
    MEDIUM("thumbnail.medium", 192),
    LARGE("thumbnail.large", 252),
    EXTRA_LARGE("thumbnail.extraLarge", 352);

    private final String key;
    private final int cardSize;

    ThumbnailSize(String key, int cardSize) {
        this.key = key;
        this.cardSize = cardSize;
    }

    public int cardSize() {
        return cardSize;
    }

    @Override
    public String toString() {
        return Messages.get(key);
    }
}
