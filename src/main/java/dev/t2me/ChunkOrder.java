package dev.t2me;

import java.util.Locale;

/** Persisted traversal identity: a cursor is meaningful only within its original order. */
public enum ChunkOrder {
    SPIRAL,
    REGION;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ChunkOrder parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public ChunkPlan createPlan(int centerBlockX, int centerBlockZ, int radiusBlocks, PregenShape shape) {
        return switch (this) {
            case SPIRAL -> new SpiralChunkPlan(centerBlockX, centerBlockZ, radiusBlocks, shape);
            case REGION -> new RegionChunkPlan(centerBlockX, centerBlockZ, radiusBlocks, shape);
        };
    }
}
