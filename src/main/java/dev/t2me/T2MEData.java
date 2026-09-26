package dev.t2me;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;

public final class T2MEData extends SavedData {
    public static final String DATA_NAME = "t2me_jobs";
    private static final Logger LOGGER = LogUtils.getLogger();

    private PregenJob.Snapshot snapshot;
    private Tag rejectedJob;
    private String loadError;

    public static T2MEData load(CompoundTag tag) {
        T2MEData data = new T2MEData();
        if (tag.contains("Job")) {
            try {
                if (!tag.contains("Job", Tag.TAG_COMPOUND)) {
                    throw new IllegalArgumentException("Job is not a compound tag");
                }
                data.snapshot = PregenJob.Snapshot.load(tag.getCompound("Job"));
            } catch (RuntimeException error) {
                data.rejectedJob = Objects.requireNonNull(tag.get("Job")).copy();
                data.loadError = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                LOGGER.error("Cannot restore T2ME checkpoint. Original job data is retained: {}",
                        data.loadError);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (rejectedJob != null) {
            tag.put("Job", rejectedJob.copy());
        } else if (snapshot != null) {
            tag.put("Job", snapshot.save());
        } else {
            tag.remove("Job");
        }
        return tag;
    }

    public Optional<PregenJob.Snapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public void snapshot(PregenJob.Snapshot snapshot) {
        if (loadError != null) {
            throw new IllegalStateException("cannot replace an unreadable checkpoint; clear it explicitly first");
        }
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        setDirty();
    }

    public Optional<String> loadError() {
        return Optional.ofNullable(loadError);
    }

    public void clear() {
        snapshot = null;
        rejectedJob = null;
        loadError = null;
        setDirty();
    }
}
