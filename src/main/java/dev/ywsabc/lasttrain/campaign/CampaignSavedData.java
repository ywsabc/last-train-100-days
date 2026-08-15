package dev.ywsabc.lasttrain.campaign;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.server.TrainRecoveryPolicy;
import java.util.EnumSet;
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
    private CampaignPacingPolicy.KeyMission activeKeyMission;
    private final Set<CampaignPacingPolicy.KeyMission> scheduledKeyMissions =
            EnumSet.noneOf(CampaignPacingPolicy.KeyMission.class);
    private ProposedMission proposedMission;
    private UUID finaleMissionId;
    private int finaleHubRouteSegment;
    private boolean finaleMissionCompleted;
    private boolean finalDayElapsed;
    private boolean starterStationBuilt;
    private long starterStationAnchor;
    private boolean starterTrainPlaced;
    private boolean starterTrainAssembled;
    private UUID starterTrainSublevelId;
    private int starterTrainAssemblyAttempts;
    private int rescueCount;
    private int lastRescueDay;
    private int trainMissingTicks;
    private int trainImmobileTicks;
    private int effectivePlayers = PopulationScalingPolicy.MIN_PLAYERS;
    private int pendingPlayers = PopulationScalingPolicy.MIN_PLAYERS;
    private int scalingHoldTicks;
    private int attention = PursuitPolicy.INITIAL_ATTENTION;
    private int pursuitDistance = PursuitPolicy.INITIAL_PURSUIT_DISTANCE;
    private int lastPursuitRouteSegment;
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
        if (tag.contains("active_key_mission")) {
            data.activeKeyMission = parseKeyMission(tag.getString("active_key_mission"));
        }
        loadKeyMissionSet(tag, "scheduled_key_missions", data.scheduledKeyMissions);
        if (tag.contains("proposed_mission")) {
            data.proposedMission = loadProposedMission(tag.getCompound("proposed_mission"));
        }
        if (tag.contains("finale_mission_id")) {
            try {
                data.finaleMissionId = UUID.fromString(tag.getString("finale_mission_id"));
            } catch (IllegalArgumentException ignored) {
                data.finaleMissionId = null;
            }
        }
        data.finaleHubRouteSegment = tag.contains("finale_hub_route_segment")
                ? Math.clamp(tag.getInt("finale_hub_route_segment"), 0, MAX_ROUTE_SEGMENT)
                : 0;
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
        data.rescueCount = Math.clamp(
                tag.getInt("rescue_count"),
                0,
                TrainRecoveryPolicy.RESCUE_COUNT_LIMIT);
        data.lastRescueDay = Math.clamp(tag.getInt("last_rescue_day"), 0, FINAL_DAY);
        data.trainMissingTicks = Math.clamp(
                tag.getInt("train_missing_ticks"),
                0,
                TrainRecoveryPolicy.MAX_TRACKED_TICKS);
        data.trainImmobileTicks = Math.clamp(
                tag.getInt("train_immobile_ticks"),
                0,
                TrainRecoveryPolicy.MAX_TRACKED_TICKS);
        data.effectivePlayers = PopulationScalingPolicy.clampPlayers(
                tag.getInt("scaling_effective_players"));
        data.pendingPlayers = PopulationScalingPolicy.clampPlayers(
                tag.getInt("scaling_pending_players"));
        data.scalingHoldTicks = Math.max(0, tag.getInt("scaling_hold_ticks"));
        data.attention = tag.contains("attention")
                ? Math.clamp(
                        tag.getInt("attention"),
                        0,
                        PursuitPolicy.MAX_ATTENTION)
                : PursuitPolicy.INITIAL_ATTENTION;
        data.pursuitDistance = tag.contains("pursuit_distance")
                ? Math.clamp(
                        tag.getInt("pursuit_distance"),
                        0,
                        PursuitPolicy.MAX_PURSUIT_DISTANCE)
                : PursuitPolicy.INITIAL_PURSUIT_DISTANCE;
        data.lastPursuitRouteSegment = tag.contains("last_pursuit_route_segment")
                ? Math.clamp(
                        tag.getInt("last_pursuit_route_segment"),
                        0,
                        MAX_ROUTE_SEGMENT)
                : data.routeSegment;
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
        if (activeKeyMission != null) {
            tag.putString("active_key_mission", activeKeyMission.name());
        }
        tag.put("scheduled_key_missions", saveKeyMissionSet(scheduledKeyMissions));
        if (proposedMission != null) {
            tag.put("proposed_mission", proposedMission.save());
        }
        if (finaleMissionId != null) {
            tag.putString("finale_mission_id", finaleMissionId.toString());
        }
        tag.putInt("finale_hub_route_segment", finaleHubRouteSegment);
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
        tag.putInt("rescue_count", rescueCount);
        tag.putInt("last_rescue_day", lastRescueDay);
        tag.putInt("train_missing_ticks", trainMissingTicks);
        tag.putInt("train_immobile_ticks", trainImmobileTicks);
        tag.putInt("scaling_effective_players", effectivePlayers);
        tag.putInt("scaling_pending_players", pendingPlayers);
        tag.putInt("scaling_hold_ticks", scalingHoldTicks);
        tag.putInt("attention", attention);
        tag.putInt("pursuit_distance", pursuitDistance);
        tag.putInt("last_pursuit_route_segment", lastPursuitRouteSegment);
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

    private static void loadKeyMissionSet(
            CompoundTag tag,
            String key,
            Set<CampaignPacingPolicy.KeyMission> target) {
        ListTag entries = tag.getList(key, Tag.TAG_STRING);
        for (int index = 0; index < entries.size(); index++) {
            CampaignPacingPolicy.KeyMission mission = parseKeyMission(entries.getString(index));
            if (mission != null) {
                target.add(mission);
            }
        }
    }

    private static ListTag saveKeyMissionSet(
            Set<CampaignPacingPolicy.KeyMission> values) {
        ListTag entries = new ListTag();
        values.stream()
                .map(Enum::name)
                .sorted()
                .map(StringTag::valueOf)
                .forEach(entries::add);
        return entries;
    }

    private static CampaignPacingPolicy.KeyMission parseKeyMission(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CampaignPacingPolicy.KeyMission.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static ProposedMission loadProposedMission(CompoundTag tag) {
        return MissionType.parse(tag.getString("type"))
                .map(type -> new ProposedMission(
                        type,
                        Math.max(1, tag.getInt("created_day")),
                        Math.max(0, tag.getInt("route_segment"))))
                .orElse(null);
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
        if (!FinalePolicy.isFinaleHubInForwardWindow(routeSegment, finaleHubRouteSegment)) {
            finaleHubRouteSegment = 0;
        }
        if (finaleMissionCompleted && isFinaleMission(activeMission)) {
            activeMission = null;
            activeKeyMission = null;
        }
        if (activeMission == null) {
            activeKeyMission = null;
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

    public void registerGunfire() {
        if (status != CampaignStatus.RUNNING) {
            return;
        }
        attention = PursuitPolicy.afterGunfireAttention(attention);
        pursuitDistance = PursuitPolicy.afterGunfirePursuit(pursuitDistance);
        setDirty();
    }

    public void registerExplosion() {
        if (status != CampaignStatus.RUNNING) {
            return;
        }
        attention = PursuitPolicy.afterExplosionAttention(attention);
        pursuitDistance = PursuitPolicy.afterExplosionPursuit(pursuitDistance);
        setDirty();
    }

    private TickOutcome updatePursuitPressure() {
        if (totalActiveTicks % PursuitPolicy.SAMPLE_INTERVAL_TICKS != 0L) {
            return TickOutcome.NONE;
        }

        PursuitPolicy.Sample next = PursuitPolicy.sample(
                attention,
                pursuitDistance,
                routeSegment,
                lastPursuitRouteSegment,
                day);
        if (next.attention() != attention
                || next.pursuitDistance() != pursuitDistance
                || next.routeSegment() != lastPursuitRouteSegment) {
            attention = next.attention();
            pursuitDistance = next.pursuitDistance();
            lastPursuitRouteSegment = next.routeSegment();
            setDirty();
        }

        if (activeTicksIntoDay >= DEFAULT_ACTIVE_TICKS_PER_DAY
                || !PursuitPolicy.shouldTriggerSiege(
                        status,
                        day,
                        next.pursuitDistance(),
                        activeMission != null)
                || !createMission(MissionType.ZOMBIE_BLOCKADE)) {
            return TickOutcome.NONE;
        }

        // The active blockade is the pressure valve. The abstract horde stays
        // at zero distance until turn-in or fallback restores it; the active
        // mission prevents a second siege while the first is unresolved.
        lastPursuitRouteSegment = routeSegment;
        setDirty();
        return TickOutcome.SIEGE_TRIGGERED;
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

        ensureFinaleHub();
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
        TickOutcome pressureOutcome = updatePursuitPressure();
        if (activeTicksIntoDay < DEFAULT_ACTIVE_TICKS_PER_DAY) {
            return pressureOutcome != TickOutcome.NONE
                    ? pressureOutcome
                    : TickOutcome.NONE;
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
        ensureFinaleHub();
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
        ensureFinaleHub();
        if (day < FINAL_DAY) {
            tryGenerateDailyMission();
        }
        setDirty();
    }

    public boolean advanceRoute(int amount) {
        if (amount <= 0 || routeSegment >= MAX_ROUTE_SEGMENT) {
            return false;
        }
        long requestedRoute = Math.min(
                MAX_ROUTE_SEGMENT,
                (long) routeSegment + amount);
        if (activeMission != null && isFinaleMission(activeMission)) {
            requestedRoute = Math.min(requestedRoute, activeMission.routeSegment());
        }
        if (requestedRoute <= routeSegment) {
            return false;
        }
        routeSegment = (int) requestedRoute;
        ensureFinaleHub();
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
        if (type == null
                || !FinalePolicy.allowsOrdinaryMission(status, day)
                || (CampaignPacingPolicy.isMainlineMission(type)
                        && !FinalePolicy.allowsOrdinaryMainlineMission(status, day))
                || activeMission != null) {
            return false;
        }
        int target = PopulationScalingPolicy.missionTarget(type, teamSize);
        activeMission = ActiveMission.create(type, day, routeSegment, target);
        activeKeyMission = null;
        missionSequence++;
        setDirty();
        return true;
    }

    /** Creates a chapter milestone only after both its day and route gates pass. */
    public boolean createKeyMission(CampaignPacingPolicy.KeyMission keyMission) {
        if (!canCreateKeyMission(keyMission)) {
            return false;
        }
        if (!createMission(keyMission.missionType(), effectivePlayers)) {
            return false;
        }
        activeKeyMission = keyMission;
        scheduledKeyMissions.add(keyMission);
        setDirty();
        return true;
    }

    public boolean canCreateKeyMission(CampaignPacingPolicy.KeyMission keyMission) {
        return keyMission != null
                && !scheduledKeyMissions.contains(keyMission)
                && activeMission == null
                && CampaignPacingPolicy.isKeyMissionEligible(
                        keyMission,
                        day,
                        routeSegment);
    }

    /** Adds a P3-style unaccepted proposal; proposals never consume the active mission slot. */
    public boolean proposeMission(MissionType type) {
        if (type == null
                || status != CampaignStatus.RUNNING
                || activeMission != null
                || proposedMission != null) {
            return false;
        }
        proposedMission = new ProposedMission(type, day, routeSegment);
        setDirty();
        return true;
    }

    public boolean clearProposedMission() {
        if (proposedMission == null) {
            return false;
        }
        proposedMission = null;
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
        MissionType completedType = activeMission.type();
        activeMission.complete();
        activeMission = null;
        activeKeyMission = null;
        if (finale) {
            finaleMissionCompleted = true;
            // Command turn-in also runs on the logical server thread. Resolve
            // an already elapsed finale immediately so completion does not
            // depend on another player-driven campaign tick.
            reconcileFinaleState();
        } else {
            threat = Math.max(0, threat - 2);
            if (completedType == MissionType.ZOMBIE_BLOCKADE) {
                pursuitDistance = Math.max(
                        pursuitDistance,
                        PursuitPolicy.PURSUIT_AFTER_SIEGE);
            }
        }
        setDirty();
        return true;
    }

    public boolean clearMission() {
        if (activeMission == null) {
            return false;
        }
        activeMission = null;
        activeKeyMission = null;
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
        MissionType failedType = activeMission.type();
        activeMission = null;
        activeKeyMission = null;
        threat = Math.min(100, threat + Math.max(0, threatPenalty));
        if (failedType == MissionType.ZOMBIE_BLOCKADE) {
            pursuitDistance = Math.max(
                    pursuitDistance,
                    PursuitPolicy.PURSUIT_AFTER_SIEGE_FALLBACK);
        }
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

    /**
     * Feeds the server-side observation of the physical train into the
     * recovery policy.
     *
     * <p>Safe-mode transitions apply regardless of player count so a broken
     * vehicle stack parks the campaign immediately. The missing and immobile
     * confirmation timers only advance while at least one non-spectator
     * player is online, and they persist across restarts so a crash cannot
     * reset the grace period. The policy only decides; relocating the
     * physical body is the vehicle backend's operation.</p>
     */
    public TrainRecoveryPolicy.Directive observeTrain(
            boolean vehicleStackLoaded,
            boolean trainReferenceKnown,
            boolean trainLocatable,
            boolean trainMoving,
            boolean playerInDangerCollision,
            int activePlayers) {
        TrainRecoveryPolicy.TrainSituation situation = new TrainRecoveryPolicy.TrainSituation(
                vehicleStackLoaded,
                trainReferenceKnown,
                trainLocatable,
                trainMoving,
                trainMissingTicks,
                trainImmobileTicks,
                playerInDangerCollision,
                rescueCount,
                lastRescueDay,
                day,
                status);
        TrainRecoveryPolicy.Directive directive = TrainRecoveryPolicy.assess(situation);

        if (directive == TrainRecoveryPolicy.Directive.SAFE_MODE
                && status == CampaignStatus.RUNNING) {
            status = CampaignStatus.SAFE_MODE;
            setDirty();
        } else if (directive != TrainRecoveryPolicy.Directive.SAFE_MODE
                && status == CampaignStatus.SAFE_MODE) {
            status = CampaignStatus.RUNNING;
            setDirty();
        }

        if (activePlayers > 0
                && (status == CampaignStatus.RUNNING
                        || status == CampaignStatus.SAFE_MODE)) {
            int nextMissingTicks = !trainLocatable && trainReferenceKnown
                    ? Math.min(
                            TrainRecoveryPolicy.MAX_TRACKED_TICKS,
                            trainMissingTicks + 1)
                    : 0;
            int nextImmobileTicks = trainLocatable && !trainMoving
                    ? Math.min(
                            TrainRecoveryPolicy.MAX_TRACKED_TICKS,
                            trainImmobileTicks + 1)
                    : 0;
            if (nextMissingTicks != trainMissingTicks
                    || nextImmobileTicks != trainImmobileTicks) {
                trainMissingTicks = nextMissingTicks;
                trainImmobileTicks = nextImmobileTicks;
                setDirty();
            }
        }
        return directive;
    }

    /**
     * Records one completed train rescue: increments the counter, starts the
     * cooldown, applies the attention and threat costs and resets the
     * detection timers. Refuses while the policy cooldown or count limit
     * blocks another rescue.
     */
    public boolean applyTrainRescue() {
        if (status != CampaignStatus.RUNNING
                && status != CampaignStatus.SAFE_MODE) {
            return false;
        }
        if (!TrainRecoveryPolicy.canRescue(rescueCount, lastRescueDay, day)) {
            return false;
        }
        rescueCount++;
        lastRescueDay = day;
        trainMissingTicks = 0;
        trainImmobileTicks = 0;
        TrainRecoveryPolicy.RescueCosts costs =
                TrainRecoveryPolicy.applyCosts(attention, threat);
        attention = costs.attention();
        threat = costs.threat();
        setDirty();
        return true;
    }

    /**
     * The farthest verified segment a rescue may return the train to. An
     * active mission's segment is a hard checkpoint: rescue is repair, not a
     * way to skip the blocker.
     */
    public int rescueAnchorSegment() {
        return TrainRecoveryPolicy.anchorSegment(
                routeSegment,
                activeMission == null ? null : activeMission.routeSegment());
    }

    private boolean tryGenerateDailyMission() {
        if (!FinalePolicy.allowsOrdinaryMission(status, day)
                || activeMission != null
                || day < 2) {
            return false;
        }

        if (tryGenerateNextKeyMission()) {
            return true;
        }

        SplittableRandom random = missionRandom(0x444159L);
        double chance = Math.min(0.85D, 0.30D + day * 0.004D);
        if (random.nextDouble() >= chance) {
            return false;
        }
        return createPacedMission(random);
    }

    private boolean tryGenerateRouteMission() {
        if (!FinalePolicy.allowsOrdinaryMission(status, day)
                || activeMission != null
                || routeSegment < 1) {
            return false;
        }

        if (tryGenerateNextKeyMission()) {
            return true;
        }

        SplittableRandom random = missionRandom(0x524f555445L ^ routeSegment);
        if (routeSegment % 3 != 0 && random.nextDouble() >= 0.35D) {
            return false;
        }
        return createPacedMission(random);
    }

    private boolean tryGenerateNextKeyMission() {
        return CampaignPacingPolicy.nextKeyMission(
                        day,
                        routeSegment,
                        scheduledKeyMissions)
                .map(this::createKeyMission)
                .orElse(false);
    }

    private boolean createPacedMission(SplittableRandom random) {
        MissionType type = CampaignPacingPolicy.selectMissionType(
                random,
                day,
                routeSegment);
        if (!FinalePolicy.allowsOrdinaryMainlineMission(status, day)
                && CampaignPacingPolicy.isMainlineMission(type)) {
            // The last-ten-day window can still offer supplies, but never
            // spends its only active slot on a fresh ordinary roadblock.
            type = MissionType.SUPPLY_RECOVERY;
        }
        return createMission(type);
    }

    private SplittableRandom missionRandom(long salt) {
        long seed = campaignSeed
                ^ salt
                ^ ((long) day << 32)
                ^ routeSegment
                ^ (missionSequence * 0x9E3779B97F4A7C15L);
        return new SplittableRandom(seed);
    }

    private TickOutcome reconcileFinaleState() {
        ensureFinaleHub();
        if (day >= FINAL_DAY
                && activeMission != null
                && !isFinaleMission(activeMission)
                && !CampaignPacingPolicy.isMainlineMission(activeMission.type())) {
            // Optional proposals/content are not allowed to consume the
            // final task slot once the finale day opens.
            activeMission = null;
            activeKeyMission = null;
            setDirty();
        }
        FinalePolicy.Directive directive = FinalePolicy.nextDirective(
                status,
                day,
                finalDayElapsed,
                finaleMissionCompleted,
                activeMission != null);
        return switch (directive) {
            case NONE -> TickOutcome.NONE;
            case CREATE_FINALE_MISSION -> {
                proposedMission = null;
                activeMission = ActiveMission.create(
                        ensureFinaleMissionId(),
                        MissionType.ZOMBIE_BLOCKADE,
                        FINAL_DAY,
                        finaleHubRouteSegment);
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

    /** Reserves a new, unexplored hub window whenever the final phase is open. */
    private boolean ensureFinaleHub() {
        if (!FinalePolicy.finaleHubWindowOpen(status, day)) {
            return false;
        }
        if (FinalePolicy.isFinaleHubInForwardWindow(routeSegment, finaleHubRouteSegment)) {
            return false;
        }
        FinalePolicy.HubWindow window = FinalePolicy.finaleHubWindow(routeSegment);
        int first = window.firstSegment();
        int last = Math.min(MAX_ROUTE_SEGMENT, window.lastSegment());
        if (first > last) {
            return false;
        }
        int candidate = Math.min(
                last,
                FinalePolicy.chooseFinaleHubSegment(campaignId, routeSegment));
        if (candidate <= routeSegment) {
            return false;
        }
        finaleHubRouteSegment = candidate;
        setDirty();
        return true;
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

    public record ProposedMission(
            MissionType type,
            int createdDay,
            int routeSegment) {
        public ProposedMission {
            type = java.util.Objects.requireNonNull(type, "type");
            createdDay = Math.max(1, createdDay);
            routeSegment = Math.max(0, routeSegment);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("type", type.serializedName());
            tag.putInt("created_day", createdDay);
            tag.putInt("route_segment", routeSegment);
            return tag;
        }
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

    public int expectedRouteSegment() {
        return CampaignPacingPolicy.expectedRouteSegment(day);
    }

    public CampaignPacingPolicy.MissionWeights pacingWeights() {
        return CampaignPacingPolicy.missionWeights(day, routeSegment);
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

    public int attention() {
        return attention;
    }

    public int pursuitDistance() {
        return pursuitDistance;
    }

    public int lastPursuitRouteSegment() {
        return lastPursuitRouteSegment;
    }

    public ActiveMission activeMission() {
        return activeMission;
    }

    public CampaignPacingPolicy.KeyMission activeKeyMission() {
        return activeKeyMission;
    }

    public Set<CampaignPacingPolicy.KeyMission> scheduledKeyMissions() {
        return Set.copyOf(scheduledKeyMissions);
    }

    public ProposedMission proposedMission() {
        return proposedMission;
    }

    public UUID finaleMissionId() {
        return finaleMissionId;
    }

    /** The reserved final hub segment; zero means the final window is not open/reserved yet. */
    public int finaleHubRouteSegment() {
        return finaleHubRouteSegment;
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

    public int rescueCount() {
        return rescueCount;
    }

    public int lastRescueDay() {
        return lastRescueDay;
    }

    public int trainMissingTicks() {
        return trainMissingTicks;
    }

    public int trainImmobileTicks() {
        return trainImmobileTicks;
    }

    public enum TickOutcome {
        NONE,
        DAY_ADVANCED,
        DAY_ADVANCED_WITH_MISSION,
        DAY_ADVANCED_WITH_FINALE,
        FINALE_MISSION_STARTED,
        FINAL_DAY_ELAPSED,
        SIEGE_TRIGGERED,
        CAMPAIGN_COMPLETED
    }
}
