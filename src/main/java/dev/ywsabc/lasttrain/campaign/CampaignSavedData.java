package dev.ywsabc.lasttrain.campaign;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * One campaign record stored in the overworld data storage.
 *
 * <p>Keeping this state on the logical server makes the exact same campaign
 * implementation work in single-player, LAN-hosted worlds and dedicated
 * servers.</p>
 */
public final class CampaignSavedData extends SavedData {
    public static final int CURRENT_SCHEMA = FinalePolicy.CURRENT_SCHEMA;
    public static final int FINAL_DAY = FinalePolicy.FINAL_DAY;
    public static final int DEFAULT_ACTIVE_TICKS_PER_DAY = 24_000;
    public static final int MAX_ROUTE_SEGMENT = 400_000;
    private static final String DATA_NAME = LastTrain.MOD_ID + "_campaign";
    private static final Factory<CampaignSavedData> FACTORY =
            new Factory<>(CampaignSavedData::new, CampaignSavedData::load);

    private int schemaVersion = CURRENT_SCHEMA;
    private UUID campaignId = UUID.randomUUID();
    private long campaignSeed;
    private CampaignStatus status = CampaignStatus.NOT_STARTED;
    private int day = 1;
    private int activeTicksIntoDay;
    private long totalActiveTicks;
    private int routeSegment;
    private int generatedRouteSegment;
    private int threat;
    private long missionSequence;
    private ActiveMission activeMission;
    private UUID finaleMissionId;
    private boolean finaleMissionCompleted;
    private boolean finalDayElapsed;
    private boolean starterStationBuilt;
    private long starterStationAnchor;
    private boolean starterTrainPlaced;
    private boolean starterTrainAssembled;
    private UUID starterTrainSublevelId;
    private int starterTrainAssemblyAttempts;
    private int effectivePlayers = PopulationScalingPolicy.MIN_PLAYERS;
    private int pendingPlayers = PopulationScalingPolicy.MIN_PLAYERS;
    private int scalingHoldTicks;
    private final Set<UUID> starterKitRecipients = new HashSet<>();
    private final Set<UUID> starterGunRecipients = new HashSet<>();

    public CampaignSavedData() {
    }

    public static CampaignSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static CampaignSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CampaignSavedData data = new CampaignSavedData();
        data.schemaVersion = tag.contains("schema_version") ? tag.getInt("schema_version") : 1;
        int loadedSchema = data.schemaVersion;
        try {
            data.campaignId = UUID.fromString(tag.getString("campaign_id"));
        } catch (IllegalArgumentException ignored) {
            data.campaignId = UUID.randomUUID();
        }
        data.campaignSeed = tag.getLong("campaign_seed");
        data.status = CampaignStatus.fromSerializedName(tag.getString("status"));
        data.day = Math.clamp(tag.getInt("day"), 1, FINAL_DAY);
        data.activeTicksIntoDay = Math.max(0, tag.getInt("active_ticks_into_day"));
        data.totalActiveTicks = Math.max(0L, tag.getLong("total_active_ticks"));
        data.routeSegment = Math.clamp(tag.getInt("route_segment"), 0, MAX_ROUTE_SEGMENT);
        data.generatedRouteSegment =
                Math.clamp(tag.getInt("generated_route_segment"), 0, MAX_ROUTE_SEGMENT);
        data.threat = Math.clamp(tag.getInt("threat"), 0, 100);
        data.missionSequence = Math.max(0L, tag.getLong("mission_sequence"));
        if (tag.contains("active_mission")) {
            data.activeMission = ActiveMission.load(tag.getCompound("active_mission"), registries);
        }
        if (tag.contains("finale_mission_id")) {
            try {
                data.finaleMissionId = UUID.fromString(tag.getString("finale_mission_id"));
            } catch (IllegalArgumentException ignored) {
                data.finaleMissionId = null;
            }
        }
        data.finaleMissionCompleted = tag.getBoolean("finale_mission_completed");
        data.finalDayElapsed = tag.getBoolean("final_day_elapsed");
        data.starterStationBuilt = tag.getBoolean("starter_station_built");
        data.starterStationAnchor = tag.getLong("starter_station_anchor");
        data.starterTrainPlaced = tag.getBoolean("starter_train_placed");
        data.starterTrainAssembled = tag.getBoolean("starter_train_assembled");
        if (tag.contains("starter_train_sublevel_id")) {
            try {
                data.starterTrainSublevelId =
                        UUID.fromString(tag.getString("starter_train_sublevel_id"));
            } catch (IllegalArgumentException ignored) {
                data.starterTrainSublevelId = null;
            }
        }
        data.starterTrainAssemblyAttempts = Math.max(0, tag.getInt("starter_train_assembly_attempts"));
        data.effectivePlayers = PopulationScalingPolicy.clampPlayers(
                tag.getInt("scaling_effective_players"));
        data.pendingPlayers = PopulationScalingPolicy.clampPlayers(
                tag.getInt("scaling_pending_players"));
        data.scalingHoldTicks = Math.max(0, tag.getInt("scaling_hold_ticks"));
        loadUuidSet(tag, "starter_kit_recipients", data.starterKitRecipients);
        loadUuidSet(tag, "starter_gun_recipients", data.starterGunRecipients);
        data.migrateFinaleState(loadedSchema);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", CURRENT_SCHEMA);
        tag.putString("campaign_id", campaignId.toString());
        tag.putLong("campaign_seed", campaignSeed);
        tag.putString("status", status.name());
        tag.putInt("day", day);
        tag.putInt("active_ticks_into_day", activeTicksIntoDay);
        tag.putLong("total_active_ticks", totalActiveTicks);
        tag.putInt("route_segment", routeSegment);
        tag.putInt("generated_route_segment", generatedRouteSegment);
        tag.putInt("threat", threat);
        tag.putLong("mission_sequence", missionSequence);
        if (activeMission != null) {
            tag.put("active_mission", activeMission.save(registries));
        }
        if (finaleMissionId != null) {
            tag.putString("finale_mission_id", finaleMissionId.toString());
        }
        tag.putBoolean("finale_mission_completed", finaleMissionCompleted);
        tag.putBoolean("final_day_elapsed", finalDayElapsed);
        tag.putBoolean("starter_station_built", starterStationBuilt);
        tag.putLong("starter_station_anchor", starterStationAnchor);
        tag.putBoolean("starter_train_placed", starterTrainPlaced);
        tag.putBoolean("starter_train_assembled", starterTrainAssembled);
        if (starterTrainSublevelId != null) {
            tag.putString("starter_train_sublevel_id", starterTrainSublevelId.toString());
        }
        tag.putInt("starter_train_assembly_attempts", starterTrainAssemblyAttempts);
        tag.putInt("scaling_effective_players", effectivePlayers);
        tag.putInt("scaling_pending_players", pendingPlayers);
        tag.putInt("scaling_hold_ticks", scalingHoldTicks);
        tag.put("starter_kit_recipients", saveUuidSet(starterKitRecipients));
        tag.put("starter_gun_recipients", saveUuidSet(starterGunRecipients));
        return tag;
    }

    private static void loadUuidSet(CompoundTag tag, String key, Set<UUID> target) {
        ListTag entries = tag.getList(key, Tag.TAG_STRING);
        for (int index = 0; index < entries.size(); index++) {
            try {
                target.add(UUID.fromString(entries.getString(index)));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed entries rather than making an existing world unloadable.
            }
        }
    }

    private static ListTag saveUuidSet(Set<UUID> values) {
        ListTag entries = new ListTag();
        values.stream()
                .map(UUID::toString)
                .sorted()
                .map(StringTag::valueOf)
                .forEach(entries::add);
        return entries;
    }

    private void migrateFinaleState(int loadedSchema) {
        FinalePolicy.MigratedState migrated = FinalePolicy.migrate(
                loadedSchema,
                status,
                day,
                finalDayElapsed,
                finaleMissionCompleted,
                activeMission != null);
        status = migrated.status();
        finalDayElapsed = migrated.finalDayElapsed();
        finaleMissionCompleted = migrated.finaleMissionCompleted();

        if (loadedSchema < 6 || day < FINAL_DAY) {
            finaleMissionId = null;
        }
        if (finaleMissionCompleted && isFinaleMission(activeMission)) {
            activeMission = null;
        }
        schemaVersion = CURRENT_SCHEMA;
    }

    public void initialize(long worldSeed) {
        if (campaignSeed == 0L) {
            campaignSeed = mixSeed(worldSeed);
            setDirty();
        }
    }

    private void observeActivePlayers(int activePlayers) {
        PopulationScalingPolicy.Hysteresis next = PopulationScalingPolicy.observe(
                new PopulationScalingPolicy.Hysteresis(
                        effectivePlayers,
                        pendingPlayers,
                        scalingHoldTicks),
                activePlayers);
        if (next.effectivePlayers() != effectivePlayers
                || next.pendingPlayers() != pendingPlayers
                || next.holdTicks() != scalingHoldTicks) {
            effectivePlayers = next.effectivePlayers();
            pendingPlayers = next.pendingPlayers();
            scalingHoldTicks = next.holdTicks();
            setDirty();
        }
    }

    public boolean start() {
        if (status != CampaignStatus.NOT_STARTED) {
            return false;
        }
        status = CampaignStatus.RUNNING;
        setDirty();
        return true;
    }

    public TickOutcome tick() {
        return tick(effectivePlayers);
    }

    public TickOutcome tick(int activePlayers) {
        if (status != CampaignStatus.RUNNING) {
            return TickOutcome.NONE;
        }

        TickOutcome finaleOutcome = reconcileFinaleState();
        if (finaleOutcome != TickOutcome.NONE) {
            return finaleOutcome;
        }

        if (finalDayElapsed) {
            return TickOutcome.NONE;
        }

        observeActivePlayers(activePlayers);
        totalActiveTicks++;
        activeTicksIntoDay++;
        if ((totalActiveTicks % 200L) == 0L) {
            setDirty();
        }
        if (activeTicksIntoDay < DEFAULT_ACTIVE_TICKS_PER_DAY) {
            return TickOutcome.NONE;
        }

        activeTicksIntoDay -= DEFAULT_ACTIVE_TICKS_PER_DAY;
        if (day >= FINAL_DAY) {
            finalDayElapsed = true;
            activeTicksIntoDay = 0;
            setDirty();
            TickOutcome completed = reconcileFinaleState();
            return completed != TickOutcome.NONE
                    ? completed
                    : TickOutcome.FINAL_DAY_ELAPSED;
        }

        day++;
        threat = Math.min(100, threat + 1 + day / 20);
        if (day >= FINAL_DAY) {
            TickOutcome started = reconcileFinaleState();
            setDirty();
            return started == TickOutcome.FINALE_MISSION_STARTED
                    ? TickOutcome.DAY_ADVANCED_WITH_FINALE
                    : TickOutcome.DAY_ADVANCED;
        }
        boolean generated = tryGenerateDailyMission();
        setDirty();
        return generated ? TickOutcome.DAY_ADVANCED_WITH_MISSION : TickOutcome.DAY_ADVANCED;
    }

    public void advanceDays(int amount) {
        if (amount <= 0
                || status == CampaignStatus.COMPLETED
                || status == CampaignStatus.FAILED) {
            return;
        }
        if (status == CampaignStatus.NOT_STARTED) {
            status = CampaignStatus.RUNNING;
        }

        day = Math.min(FINAL_DAY, day + amount);
        threat = Math.min(100, threat + amount + day / 20);
        activeTicksIntoDay = 0;
        if (day < FINAL_DAY) {
            tryGenerateDailyMission();
        }
        setDirty();
    }

    public boolean advanceRoute(int amount) {
        if (amount <= 0 || routeSegment >= MAX_ROUTE_SEGMENT) {
            return false;
        }
        routeSegment = (int) Math.min(
                MAX_ROUTE_SEGMENT,
                (long) routeSegment + amount);
        if (activeMission == null) {
            tryGenerateRouteMission();
        }
        setDirty();
        return true;
    }

    public boolean advanceRouteTo(int segment) {
        if (segment <= routeSegment) {
            return false;
        }
        return advanceRoute(segment - routeSegment);
    }

    public boolean markRouteSegmentGenerated(int segment) {
        if (segment <= generatedRouteSegment) {
            return false;
        }
        if (segment != generatedRouteSegment + 1) {
            throw new IllegalArgumentException(
                    "Route segments must be committed in order: expected "
                            + (generatedRouteSegment + 1)
                            + ", got "
                            + segment);
        }
        generatedRouteSegment = segment;
        setDirty();
        return true;
    }

    public boolean createMission(MissionType type) {
        return createMission(type, effectivePlayers);
    }

    public boolean createMission(MissionType type, int teamSize) {
        if (!FinalePolicy.allowsOrdinaryMission(status, day)
                || activeMission != null) {
            return false;
        }
        int target = PopulationScalingPolicy.missionTarget(type, teamSize);
        activeMission = ActiveMission.create(type, day, routeSegment, target);
        missionSequence++;
        setDirty();
        return true;
    }

    public boolean addMissionProgress(int amount) {
        if (activeMission == null || !activeMission.addProgress(amount)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean setMissionObservedProgress(int progress) {
        if (activeMission == null || !activeMission.setObservedProgress(progress)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean assignMissionSite(BlockPos site) {
        if (activeMission == null || !activeMission.assignSite(site)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean markMissionWorldPrepared() {
        if (activeMission == null || !activeMission.markWorldPrepared()) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean turnInMission() {
        if (activeMission == null || activeMission.stage() != MissionStage.READY_TO_TURN_IN) {
            return false;
        }
        boolean finale = isFinaleMission(activeMission);
        activeMission.complete();
        activeMission = null;
        if (finale) {
            finaleMissionCompleted = true;
            // Command turn-in also runs on the logical server thread. Resolve
            // an already elapsed finale immediately so completion does not
            // depend on another player-driven campaign tick.
            reconcileFinaleState();
        } else {
            threat = Math.max(0, threat - 2);
        }
        setDirty();
        return true;
    }

    public boolean clearMission() {
        if (activeMission == null) {
            return false;
        }
        activeMission = null;
        setDirty();
        return true;
    }

    /**
     * Fails the active ordinary mission and applies the fallback penalty.
     * Finale missions are the campaign completion gate and are never failed
     * by this path; admins can still explicitly clear one for debugging.
     */
    public boolean failMission(int threatPenalty) {
        if (activeMission == null || isFinaleMission(activeMission)) {
            return false;
        }
        activeMission = null;
        threat = Math.min(100, threat + Math.max(0, threatPenalty));
        setDirty();
        return true;
    }

    public boolean claimStarterKit(UUID playerId) {
        if (!starterKitRecipients.add(playerId)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean hasClaimedStarterGun(UUID playerId) {
        return starterGunRecipients.contains(playerId);
    }

    public boolean claimStarterGun(UUID playerId) {
        if (!starterGunRecipients.add(playerId)) {
            return false;
        }
        setDirty();
        return true;
    }

    public void markStarterStationBuilt(BlockPos anchor) {
        starterStationBuilt = true;
        starterStationAnchor = anchor.asLong();
        setDirty();
    }

    public void markStarterTrainPlaced() {
        starterTrainPlaced = true;
        setDirty();
    }

    public int recordStarterTrainAssemblyAttempt() {
        starterTrainAssemblyAttempts++;
        setDirty();
        return starterTrainAssemblyAttempts;
    }

    public void markStarterTrainAssembled(UUID sublevelId) {
        starterTrainPlaced = true;
        starterTrainAssembled = true;
        starterTrainSublevelId = sublevelId;
        setDirty();
    }

    private boolean tryGenerateDailyMission() {
        if (!FinalePolicy.allowsOrdinaryMission(status, day)
                || activeMission != null
                || day < 2) {
            return false;
        }

        SplittableRandom random = missionRandom(0x444159L);
        double chance = Math.min(0.85D, 0.30D + day * 0.004D);
        if (random.nextDouble() >= chance) {
            return false;
        }
        return createMission(randomMissionType(random));
    }

    private boolean tryGenerateRouteMission() {
        if (!FinalePolicy.allowsOrdinaryMission(status, day)
                || activeMission != null
                || routeSegment < 1) {
            return false;
        }

        SplittableRandom random = missionRandom(0x524f555445L ^ routeSegment);
        if (routeSegment % 3 != 0 && random.nextDouble() >= 0.35D) {
            return false;
        }
        return createMission(randomMissionType(random));
    }

    private SplittableRandom missionRandom(long salt) {
        long seed = campaignSeed
                ^ salt
                ^ ((long) day << 32)
                ^ routeSegment
                ^ (missionSequence * 0x9E3779B97F4A7C15L);
        return new SplittableRandom(seed);
    }

    private static MissionType randomMissionType(SplittableRandom random) {
        MissionType[] types = MissionType.values();
        return types[random.nextInt(types.length)];
    }

    private TickOutcome reconcileFinaleState() {
        FinalePolicy.Directive directive = FinalePolicy.nextDirective(
                status,
                day,
                finalDayElapsed,
                finaleMissionCompleted,
                activeMission != null);
        return switch (directive) {
            case NONE -> TickOutcome.NONE;
            case CREATE_FINALE_MISSION -> {
                activeMission = ActiveMission.create(
                        ensureFinaleMissionId(),
                        MissionType.ZOMBIE_BLOCKADE,
                        FINAL_DAY,
                        routeSegment);
                setDirty();
                yield TickOutcome.FINALE_MISSION_STARTED;
            }
            case COMPLETE_CAMPAIGN -> {
                status = CampaignStatus.COMPLETED;
                setDirty();
                yield TickOutcome.CAMPAIGN_COMPLETED;
            }
        };
    }

    private UUID ensureFinaleMissionId() {
        if (finaleMissionId == null) {
            finaleMissionId = FinalePolicy.missionId(campaignId);
        }
        return finaleMissionId;
    }

    public boolean isFinaleMission(ActiveMission mission) {
        return mission != null
                && finaleMissionId != null
                && finaleMissionId.equals(mission.id());
    }

    private static long mixSeed(long seed) {
        long mixed = seed ^ 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    public UUID campaignId() {
        return campaignId;
    }

    public CampaignStatus status() {
        return status;
    }

    public int day() {
        return day;
    }

    public int activeTicksIntoDay() {
        return activeTicksIntoDay;
    }

    public long totalActiveTicks() {
        return totalActiveTicks;
    }

    public int routeSegment() {
        return routeSegment;
    }

    public int generatedRouteSegment() {
        return generatedRouteSegment;
    }

    public int threat() {
        return threat;
    }

    public int effectivePlayers() {
        return effectivePlayers;
    }

    public ActiveMission activeMission() {
        return activeMission;
    }

    public UUID finaleMissionId() {
        return finaleMissionId;
    }

    public boolean finaleMissionCompleted() {
        return finaleMissionCompleted;
    }

    public boolean finalDayElapsed() {
        return finalDayElapsed;
    }

    public boolean starterStationBuilt() {
        return starterStationBuilt;
    }

    public BlockPos starterStationAnchor() {
        return BlockPos.of(starterStationAnchor);
    }

    public boolean starterTrainPlaced() {
        return starterTrainPlaced;
    }

    public boolean starterTrainAssembled() {
        return starterTrainAssembled;
    }

    public UUID starterTrainSublevelId() {
        return starterTrainSublevelId;
    }

    public int starterTrainAssemblyAttempts() {
        return starterTrainAssemblyAttempts;
    }

    public enum TickOutcome {
        NONE,
        DAY_ADVANCED,
        DAY_ADVANCED_WITH_MISSION,
        DAY_ADVANCED_WITH_FINALE,
        FINALE_MISSION_STARTED,
        FINAL_DAY_ELAPSED,
        CAMPAIGN_COMPLETED
    }
}
