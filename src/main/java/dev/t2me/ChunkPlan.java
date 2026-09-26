package dev.t2me;

/** A deterministic traversal whose cursor counts inspected candidate slots. */
public interface ChunkPlan {
    boolean hasNext();

    long nextPacked();

    long cursor();

    void cursor(long cursor);

    long targetCount();

    long candidateCount();

    int centerBlockX();

    int centerBlockZ();

    int radiusBlocks();

    PregenShape shape();

    long acceptedBefore(long cursor);

    boolean wasVisited(long packed, long cursor);
}
