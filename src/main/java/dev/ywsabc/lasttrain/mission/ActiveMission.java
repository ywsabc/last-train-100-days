package dev.ywsabc.lasttrain.mission;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * One persisted mission instance.
 *
 * <p>Mainline missions live in the single route-blocking slot. Optional
 * missions are created as {@link MissionStage#PROPOSED} with an absolute tick
 * deadline captured from the campaign's monotonic active-tick counter, then
 * moved to the optional list on acceptance. Salvage-car repair progress is an
 * index set: repairing the same damage point twice counts once, and the set
 * survives restarts independently of the world blocks.</p>
 */
public final class ActiveMission {
    static final int MAX_REPAIR_INDICES = 64;
    static final int MAX_TARGET = 4_096;

    private final UUID id;
    private final MissionType type;
    private final int createdDay;
    private final int routeSegment;
    private final int target;
    private final long createdTick;
    private final long deadlineTick;
    private final Set<Integer> repairedIndices = new LinkedHashSet<>();
    /** UUID 白名单随任务快照持久化，实体离开现场或卸载区块后仍占用名额。 */
    private final Set<UUID> entityIds = new LinkedHashSet<>();
    private MissionStage stage;
    private int revision;
    private int progress;
    private BlockPos site;
    private boolean worldPrepared;
    /** 旧存档兼容认领只运行一次；正常路径之后完全按 UUID 索引工作。 */
    private boolean entityIndexInitialized;

    private ActiveMission(
            UUID id,
            MissionType type,
            MissionStage stage,
            int revision,
            int createdDay,
            int routeSegment,
            int progress,
            int target,
            BlockPos site,
            boolean worldPrepared,
            long createdTick,
            long deadlineTick) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.stage = Objects.requireNonNull(stage, "stage");
        this.revision = Math.max(0, revision);
        this.createdDay = Math.max(1, createdDay);
        this.routeSegment = Math.max(0, routeSegment);
        this.target = Math.clamp(Math.max(1, target), 1, MAX_TARGET);
        this.progress = Math.clamp(progress, 0, this.target);
        this.site = site == null ? null : site.immutable();
        // 没有稳定现场坐标就不能声称世界准备完成；旧档异常组合会在下一 tick
        // 重新分配现场并走幂等准备，而不是直接观察空气后误判完成。
        this.worldPrepared = worldPrepared && this.site != null;
        this.entityIndexInitialized = false;
        this.createdTick = Math.max(0L, createdTick);
        this.deadlineTick = Math.max(this.createdTick, deadlineTick);
        normalizeStage();
    }

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
        this(
                id,
                type,
                stage,
                0,
                createdDay,
                routeSegment,
                progress,
                target,
                site,
                worldPrepared,
                0L,
                Long.MAX_VALUE);
    }

    public static ActiveMission create(MissionType type, int day, int routeSegment) {
        return create(UUID.randomUUID(), type, day, routeSegment);
    }

    public static ActiveMission create(
            UUID id,
            MissionType type,
            int day,
            int routeSegment) {
        return create(
                id,
                type,
                day,
                routeSegment,
                type.defaultTarget());
    }

    public static ActiveMission create(
            MissionType type,
            int day,
            int routeSegment,
            int target) {
        return create(
                UUID.randomUUID(),
                type,
                day,
                routeSegment,
                target);
    }

    public static ActiveMission create(
            UUID id,
            MissionType type,
            int day,
            int routeSegment,
            int target) {
        return new ActiveMission(
                id,
                type,
                MissionStage.ACTIVE,
                day,
                routeSegment,
                0,
                target,
                null,
                false);
    }

    /**
     * Creates an optional mission proposal. The deadline is an absolute
     * monotonic tick captured at creation time, never a capped day
     * difference, so proposals made on day 99 still settle on day 100.
     */
    public static ActiveMission createProposal(
            MissionType type,
            int day,
            int routeSegment,
            long createdTick) {
        return createProposal(
                UUID.randomUUID(),
                type,
                day,
                routeSegment,
                type.defaultTarget(),
                createdTick);
    }

    public static ActiveMission createProposal(
            UUID id,
            MissionType type,
            int day,
            int routeSegment,
            int target,
            long createdTick) {
        if (!OptionalMissionPolicy.isOptional(type)) {
            throw new IllegalArgumentException(
                    type + " cannot be proposed as an optional mission");
        }
        return new ActiveMission(
                id,
                type,
                MissionStage.PROPOSED,
                0,
                day,
                routeSegment,
                0,
                target,
                null,
                false,
                createdTick,
                OptionalMissionPolicy.deadlineTick(createdTick, type));
    }

    public static ActiveMission load(CompoundTag tag, HolderLookup.Provider registries) {
        UUID id;
        try {
            id = UUID.fromString(tag.getString("id"));
        } catch (IllegalArgumentException ignored) {
            id = UUID.randomUUID();
        }

        MissionType type = MissionType.parse(tag.getString("type")).orElse(MissionType.RAIL_BREAK);
        int target = tag.contains("target") ? tag.getInt("target") : type.defaultTarget();
        ActiveMission mission = new ActiveMission(
                id,
                type,
                MissionStage.fromSerializedName(tag.getString("stage")),
                tag.getInt("revision"),
                tag.getInt("created_day"),
                tag.getInt("route_segment"),
                tag.getInt("progress"),
                target,
                tag.contains("site") ? BlockPos.of(tag.getLong("site")) : null,
                tag.getBoolean("world_prepared"),
                tag.getLong("created_tick"),
                tag.contains("deadline_tick")
                        ? tag.getLong("deadline_tick")
                        : Long.MAX_VALUE);
        for (int index : tag.getIntArray("repair_indices")) {
            mission.recordRepairIndex(index);
        }
        mission.entityIndexInitialized = tag.getBoolean("entity_index_initialized");
        ListTag entityIds = tag.getList("entity_ids", Tag.TAG_STRING);
        for (int index = 0;
                index < entityIds.size()
                        && mission.entityIds.size()
                                < MissionEntityContainer.MAX_REGISTERED_ENTITIES;
                index++) {
            try {
                mission.entityIds.add(UUID.fromString(entityIds.getString(index)));
            } catch (IllegalArgumentException ignored) {
                // 损坏 UUID 只丢弃本项，不能让整个任务快照无法加载。
            }
        }
        mission.normalizeStage();
        return mission;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id.toString());
        tag.putString("type", type.serializedName());
        tag.putString("stage", stage.name());
        tag.putInt("revision", revision);
        tag.putInt("created_day", createdDay);
        tag.putInt("route_segment", routeSegment);
        tag.putInt("progress", progress);
        tag.putInt("target", target);
        if (site != null) {
            tag.putLong("site", site.asLong());
        }
        tag.putBoolean("world_prepared", worldPrepared);
        tag.putBoolean("entity_index_initialized", entityIndexInitialized);
        tag.putLong("created_tick", createdTick);
        tag.putLong("deadline_tick", deadlineTick);
        tag.putIntArray(
                "repair_indices",
                repairedIndices.stream().sorted().mapToInt(Integer::intValue).toArray());
        ListTag entities = new ListTag();
        entityIds.stream()
                .sorted()
                .map(UUID::toString)
                .map(StringTag::valueOf)
                .forEach(entities::add);
        tag.put("entity_ids", entities);
        return tag;
    }

    /**
     * State migration with optimistic-concurrency protection: every transition
     * bumps the revision so stale accept/reject/skip answers are rejected
     * before their effect is applied.
     */
    public boolean transitionTo(MissionStage nextStage) {
        Objects.requireNonNull(nextStage, "nextStage");
        if (stage == nextStage) {
            return false;
        }
        stage = nextStage;
        revision++;
        return true;
    }

    /**
     * Records one repaired salvage damage point. The index set is the
     * authority: the same position repaired twice counts once, and out-of
     * range or already recorded indices change nothing.
     */
    public boolean recordRepairIndex(int index) {
        if (stage.terminal() || index < 0 || index >= target) {
            return false;
        }
        if (repairedIndices.size() >= MAX_REPAIR_INDICES) {
            return false;
        }
        if (!repairedIndices.add(index)) {
            return false;
        }
        normalizeStage();
        return true;
    }

    public boolean hasRepairIndex(int index) {
        return repairedIndices.contains(index);
    }

    public Set<Integer> repairedIndices() {
        return Set.copyOf(repairedIndices);
    }

    /** 由 CampaignSavedData 在检查全局硬上限后调用。 */
    public boolean registerEntity(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId");
        if (entityIds.contains(entityId)) {
            return false;
        }
        if (entityIds.size() >= MissionEntityContainer.MAX_REGISTERED_ENTITIES) {
            return false;
        }
        return entityIds.add(entityId);
    }

    public boolean unregisterEntity(UUID entityId) {
        return entityId != null && entityIds.remove(entityId);
    }

    public Set<UUID> entityIds() {
        return Set.copyOf(entityIds);
    }

    public boolean markEntityIndexInitialized() {
        if (entityIndexInitialized) {
            return false;
        }
        entityIndexInitialized = true;
        return true;
    }

    public boolean addProgress(int amount) {
        if (stage.terminal()
                || type == MissionType.SALVAGE_CAR
                || amount <= 0) {
            return false;
        }

        int previous = progress;
        progress = Math.min(target, progress + amount);
        normalizeStage();
        return progress != previous;
    }

    public boolean setObservedProgress(int observedProgress) {
        if (stage.terminal() || type == MissionType.SALVAGE_CAR) {
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
        revision++;
    }

    private void normalizeStage() {
        if (stage.terminal()
                || stage == MissionStage.PROPOSED
                || stage == MissionStage.REWARD_PENDING) {
            return;
        }
        stage = progress() >= target
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

    public int revision() {
        return revision;
    }

    public int createdDay() {
        return createdDay;
    }

    public int routeSegment() {
        return routeSegment;
    }

    public int progress() {
        return type == MissionType.SALVAGE_CAR ? repairedIndices.size() : progress;
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

    public boolean entityIndexInitialized() {
        return entityIndexInitialized;
    }

    public long createdTick() {
        return createdTick;
    }

    public long deadlineTick() {
        return deadlineTick;
    }

    public boolean isOptional() {
        return type.category() == MissionType.Category.OPTIONAL;
    }
}
