package dev.t2me;

import java.util.Locale;

public enum PregenShape {
    CIRCLE,
    SQUARE;

    public static PregenShape parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
