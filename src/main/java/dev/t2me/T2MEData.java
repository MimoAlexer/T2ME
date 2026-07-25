package dev.t2me;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Optional;

public final class T2MEData extends SavedData {
    public static final String DATA_NAME = "t2me_jobs";

    private PregenJob.Snapshot snapshot;

    public static T2MEData load(CompoundTag tag) {
        T2MEData data = new T2MEData();
        if (tag.contains("Job", Tag.TAG_COMPOUND)) {
            try {
                data.snapshot = PregenJob.Snapshot.load(tag.getCompound("Job"));
            } catch (RuntimeException ignored) {
                data.snapshot = null;
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (snapshot != null) {
            tag.put("Job", snapshot.save());
        }
        return tag;
    }

    public Optional<PregenJob.Snapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public void snapshot(PregenJob.Snapshot snapshot) {
        this.snapshot = snapshot;
        setDirty();
    }

    public void clear() {
        snapshot = null;
        setDirty();
    }
}
