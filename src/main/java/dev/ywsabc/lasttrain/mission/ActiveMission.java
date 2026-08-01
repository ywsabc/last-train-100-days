package dev.ywsabc.lasttrain.mission;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

public final class ActiveMission {
    private final UUID id;
    private final MissionType type;
    private final int createdDay;
    private final int routeSegment;
    private final int target;
    private MissionStage stage;
    private int progress;
    private BlockPos site;
    private boolean worldPrepared;

    public ActiveMission(
            UUID id,
            MissionType type,
            MissionStage stage,
            int createdDay,
            int routeSegment,
            int progress,
            int target,
            BlockPos site,
            boolean worldPrepared) {
        this.id = id;
        this.type = type;
        this.stage = stage;
        this.createdDay = createdDay;
        this.routeSegment = routeSegment;
        this.progress = Math.max(0, progress);
        this.target = Math.max(1, target);
        this.site = site;
        this.worldPrepared = worldPrepared;
        normalizeStage();
    }

    public static ActiveMission create(MissionType type, int day, int routeSegment) {
        return new ActiveMission(
                UUID.randomUUID(),
                type,
                MissionStage.ACTIVE,
                day,
                routeSegment,
                0,
                type.defaultTarget(),
                null,
                false);
    }

    public static ActiveMission load(CompoundTag tag, HolderLookup.Provider registries) {
        UUID id;
        try {
            id = UUID.fromString(tag.getString("id"));
        } catch (IllegalArgumentException ignored) {
            id = UUID.randomUUID();
        }

        MissionType type = MissionType.parse(tag.getString("type")).orElse(MissionType.RAIL_BREAK);
        return new ActiveMission(
                id,
                type,
                MissionStage.fromSerializedName(tag.getString("stage")),
                tag.getInt("created_day"),
                tag.getInt("route_segment"),
                tag.getInt("progress"),
                tag.contains("target") ? tag.getInt("target") : type.defaultTarget(),
                tag.contains("site") ? BlockPos.of(tag.getLong("site")) : null,
                tag.getBoolean("world_prepared"));
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id.toString());
        tag.putString("type", type.serializedName());
        tag.putString("stage", stage.name());
        tag.putInt("created_day", createdDay);
        tag.putInt("route_segment", routeSegment);
        tag.putInt("progress", progress);
        tag.putInt("target", target);
        if (site != null) {
            tag.putLong("site", site.asLong());
        }
        tag.putBoolean("world_prepared", worldPrepared);
        return tag;
    }

    public boolean addProgress(int amount) {
        if (stage == MissionStage.COMPLETED || stage == MissionStage.FAILED || amount <= 0) {
            return false;
        }

        int previous = progress;
        progress = Math.min(target, progress + amount);
        normalizeStage();
        return progress != previous;
    }

    public boolean setObservedProgress(int observedProgress) {
        if (stage == MissionStage.COMPLETED || stage == MissionStage.FAILED) {
            return false;
        }

        int normalized = Math.clamp(observedProgress, 0, target);
        if (normalized == progress) {
            return false;
        }
        progress = normalized;
        normalizeStage();
        return true;
    }

    public boolean assignSite(BlockPos newSite) {
        if (site != null) {
            return false;
        }
        site = newSite.immutable();
        return true;
    }

    public boolean markWorldPrepared() {
        if (worldPrepared) {
            return false;
        }
        worldPrepared = true;
        return true;
    }

    public void complete() {
        progress = target;
        stage = MissionStage.COMPLETED;
    }

    private void normalizeStage() {
        if (stage == MissionStage.COMPLETED || stage == MissionStage.FAILED) {
            return;
        }
        stage = progress >= target
                ? MissionStage.READY_TO_TURN_IN
                : MissionStage.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public MissionType type() {
        return type;
    }

    public MissionStage stage() {
        return stage;
    }

    public int createdDay() {
        return createdDay;
    }

    public int routeSegment() {
        return routeSegment;
    }

    public int progress() {
        return progress;
    }

    public int target() {
        return target;
    }

    public BlockPos site() {
        return site;
    }

    public boolean worldPrepared() {
        return worldPrepared;
    }
}
