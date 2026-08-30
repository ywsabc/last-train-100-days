package dev.ywsabc.lasttrain.campaign;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionPoolPolicy;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.mission.OptionalMissionPolicy;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy;
import dev.ywsabc.lasttrain.route.RouteExit;
import dev.ywsabc.lasttrain.route.RouteExitKind;
import dev.ywsabc.lasttrain.route.RouteGeometry;
import dev.ywsabc.lasttrain.route.RouteProgressPolicy;
import dev.ywsabc.lasttrain.route.RouteSegmentPlan;
import dev.ywsabc.lasttrain.route.RouteSegmentPlanner;
import dev.ywsabc.lasttrain.route.RouteTemplateConfig;
import dev.ywsabc.lasttrain.route.SegmentTemplate;
import dev.ywsabc.lasttrain.server.TrainRecoveryPolicy;
import dev.ywsabc.lasttrain.testing.FaultInjection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
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
    public static final int MAX_OPTIONAL_MISSIONS_ON_LOAD = 8;
    public static final int MAX_PENDING_CLEANUPS = 64;
    public static final int MAX_TEAM_MEMBERS = 128;
    /** How many segments ahead of the realized head the planner commits. */
    public static final int DEFAULT_ROUTE_PLAN_AHEAD = 8;
    /** Hard deserialization cap for the pending route plan list. */
    public static final int MAX_ROUTE_PLANS_ON_LOAD = 1024;
    private static final String DATA_NAME = LastTrain.MOD_ID + "_campaign";
    private static final Factory<CampaignSavedData> FACTORY =
            new Factory<>(CampaignSavedData::new, CampaignSavedData::load);

    private int schemaVersion = CURRENT_SCHEMA;
    private UUID campaignId = UUID.randomUUID();
    private long campaignSeed;
    /**
     * Deterministic route rules version this campaign commits its plans with.
     * New campaigns start on {@link RouteSegmentPlanner#DEFAULT_ROUTE_RULES_VERSION};
     * saves without the key are old worlds and keep version 1 until an
     * explicit {@link #adoptRouteRulesVersion} migration.
     */
    private int routeRulesVersion = RouteSegmentPlanner.DEFAULT_ROUTE_RULES_VERSION;
    /** SHA-256 salt halves of the config used for the last plan extension. */
    private long routePlanConfigSaltLo;
    private long routePlanConfigSaltHi;
    /** Persisted rolling planner state after the last planned segment. */
    private RouteSegmentPlanner.Cursor routePlanCursor = RouteSegmentPlanner.Cursor.fresh();
    /** Pending plans for segments above {@code generatedRouteSegment}. */
    private final List<RouteSegmentPlan> routePlans = new ArrayList<>();
    /** Memory-only planner, rebuilt lazily from the persisted state. */
    private RouteSegmentPlanner routePlanner;
    private CampaignMode mode = CampaignMode.STORY_100_DAYS;
    private CampaignStatus status = CampaignStatus.NOT_STARTED;
    private int day = 1;
    private int activeTicksIntoDay;
    private long totalActiveTicks;
    /**
     * Active ticks per campaign day. Memory-only — never persisted — and only
     * changed by the explicit test-only fast-forward opt-in
     * ({@link FastForwardMode}). A reload always returns to
     * {@link #DEFAULT_ACTIVE_TICKS_PER_DAY}; the production server tick loop
     * never writes this field.
     */
    private int activeTicksPerDay = DEFAULT_ACTIVE_TICKS_PER_DAY;
    private boolean fastForwardEnabled;
    private int routeSegment;
    private int generatedRouteSegment;
    private int threat;
    private long missionSequence;
    private ActiveMission activeMission;
    private CampaignPacingPolicy.KeyMission activeKeyMission;
    private final Set<CampaignPacingPolicy.KeyMission> scheduledKeyMissions =
            EnumSet.noneOf(CampaignPacingPolicy.KeyMission.class);
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
    /** Schema 11：只按有效在线 tick 与关键事件单调推进，不由 day 反算。 */
    private int infectionStage = InfectionPolicy.MIN_STAGE;
    private long infectionTicks;
    private ActiveMission proposedMission;
    private final List<ActiveMission> optionalMissions = new ArrayList<>();
    private final Map<UUID, RewardReceipt> rewardReceipts = new LinkedHashMap<>();
    private final List<PendingSiteCleanup> pendingSiteCleanups = new ArrayList<>();
    private final List<MissionPoolPolicy.Entry> missionHistory = new ArrayList<>();
    private final Set<UUID> teamMembers = new LinkedHashSet<>();
    private UUID captainId;
    private long captainTransferTick;
    private long captainOfflineSinceTick = -1L;
    private boolean captainOnline = true;
    private TeamPermissionPolicy.Vote pendingTeamVote;
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
        // Route rules version: a save without the key is a pre-planner world
        // and commits to the historical version 1; it never auto-upgrades.
        data.routeRulesVersion = tag.contains("route_rules_version")
                ? Math.max(1, tag.getInt("route_rules_version"))
                : 1;
        data.mode = CampaignMode.fromSerializedName(tag.getString("mode"));
        data.status = CampaignStatus.fromSerializedName(tag.getString("status"));
        data.day = Math.clamp(tag.getInt("day"), 1, maxDay(data.mode));
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
        data.lastRescueDay = Math.clamp(tag.getInt("last_rescue_day"), 0, maxDay(data.mode));
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
        // Schema 11 缺省必须保持阶段 0 / tick 0。尤其不能拿旧档的 day 或
        // total_active_ticks 回填，否则迁移会静默改变既有战役难度。
        data.infectionStage = tag.contains("infection_stage")
                ? Math.clamp(
                        tag.getInt("infection_stage"),
                        InfectionPolicy.MIN_STAGE,
                        InfectionPolicy.MAX_STAGE)
                : InfectionPolicy.MIN_STAGE;
        data.infectionTicks = tag.contains("infection_ticks")
                ? Math.max(0L, tag.getLong("infection_ticks"))
                : 0L;
        loadUuidSet(tag, "starter_kit_recipients", data.starterKitRecipients);
        loadUuidSet(tag, "starter_gun_recipients", data.starterGunRecipients);
        loadOptionalState(tag, registries, data);
        loadRoutePlanState(tag, data);
        data.migrateFinaleState(loadedSchema);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", CURRENT_SCHEMA);
        tag.putString("campaign_id", campaignId.toString());
        tag.putLong("campaign_seed", campaignSeed);
        tag.putInt("route_rules_version", routeRulesVersion);
        tag.putString("mode", mode.serializedName());
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
        tag.putInt("infection_stage", infectionStage);
        tag.putLong("infection_ticks", infectionTicks);
        tag.put("starter_kit_recipients", saveUuidSet(starterKitRecipients));
        tag.put("starter_gun_recipients", saveUuidSet(starterGunRecipients));
        saveOptionalState(tag, registries);
        saveRoutePlanState(tag);
        return tag;
    }

    /**
     * Loads the optional-mission state added in schema 7. All collections are
     * capped during deserialization; missing keys keep safe empty defaults so
     * every older save loads unchanged. P5 vote state is deliberately not
     * restored here: a pending vote is persisted for the current SavedData
     * snapshot but is cancelled on restart. Reward receipts over the cap drop
     * oldest {@link RewardOutboxPolicy.ReceiptState#CLAIMED} entries first and
     * never evict an undelivered PENDING receipt; a save with more PENDING
     * receipts than the cap keeps every receipt and parks a running campaign
     * in {@link CampaignStatus#SAFE_MODE}.
     */
    private static void loadOptionalState(
            CompoundTag tag,
            HolderLookup.Provider registries,
            CampaignSavedData data) {
        if (tag.contains("proposed_mission")) {
            ActiveMission proposed =
                    ActiveMission.load(tag.getCompound("proposed_mission"), registries);
            if (proposed.stage() == MissionStage.PROPOSED) {
                data.proposedMission = proposed;
            }
        }
        ListTag missions = tag.getList("optional_missions", Tag.TAG_COMPOUND);
        for (int index = 0; index < missions.size(); index++) {
            if (FaultInjection.shouldFail(FaultInjection.FailurePoint.SAVE_LOAD_CORRUPT_ENTRY)) {
                // Injected corruption: the entry is dropped exactly like a
                // malformed record, so a faulty save can never stop loading.
                continue;
            }
            if (data.optionalMissions.size() >= MAX_OPTIONAL_MISSIONS_ON_LOAD) {
                LastTrain.LOGGER.warn(
                        "Optional mission list exceeded the load cap of {}; "
                                + "extra entries were dropped",
                        MAX_OPTIONAL_MISSIONS_ON_LOAD);
                break;
            }
            ActiveMission mission = ActiveMission.load(missions.getCompound(index), registries);
            if (mission.stage().terminal() || mission.stage() == MissionStage.PROPOSED) {
                // Terminal missions belong to history/cleanup; a PROPOSED
                // stage in the optional list is corrupt data and is dropped.
                continue;
            }
            data.optionalMissions.add(mission);
        }
        loadRewardReceipts(tag, data);
        ListTag cleanups = tag.getList("pending_cleanups", Tag.TAG_COMPOUND);
        for (int index = 0; index < cleanups.size(); index++) {
            if (data.pendingSiteCleanups.size() >= MAX_PENDING_CLEANUPS) {
                break;
            }
            PendingSiteCleanup cleanup = PendingSiteCleanup.load(cleanups.getCompound(index));
            if (cleanup != null) {
                data.pendingSiteCleanups.add(cleanup);
            }
        }
        ListTag history = tag.getList("mission_history", Tag.TAG_COMPOUND);
        for (int index = 0; index < history.size(); index++) {
            MissionPoolPolicy.Entry entry = loadHistoryEntry(history.getCompound(index));
            if (entry != null) {
                data.missionHistory.add(entry);
            }
        }
        while (data.missionHistory.size() > MissionPoolPolicy.HISTORY_LIMIT) {
            data.missionHistory.remove(0);
        }
        ListTag members = tag.getList("team_members", Tag.TAG_STRING);
        for (int index = 0; index < members.size(); index++) {
            if (data.teamMembers.size() >= MAX_TEAM_MEMBERS) {
                break;
            }
            try {
                data.teamMembers.add(UUID.fromString(members.getString(index)));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed entries rather than making the save unloadable.
            }
        }
        if (tag.contains("captain_id")) {
            try {
                data.captainId = UUID.fromString(tag.getString("captain_id"));
            } catch (IllegalArgumentException ignored) {
                data.captainId = null;
            }
        }
        data.captainTransferTick = Math.max(0L, tag.getLong("captain_transfer_tick"));
        data.captainOfflineSinceTick = tag.contains("captain_offline_since_tick")
                ? Math.max(-1L, tag.getLong("captain_offline_since_tick"))
                : -1L;
        data.captainOnline = data.captainOfflineSinceTick < 0L;
        if (data.captainId != null && !data.teamMembers.contains(data.captainId)) {
            // A malformed or pre-P5 save must not grant captain powers to an
            // UUID that is not actually in this campaign's team.
            data.captainId = null;
        }
        if (data.captainId == null && !data.teamMembers.isEmpty()) {
            data.captainId = data.teamMembers.iterator().next();
        }
        if (data.captainId == null) {
            data.captainOfflineSinceTick = -1L;
            data.captainOnline = true;
        }
    }

    private static void loadRewardReceipts(CompoundTag tag, CampaignSavedData data) {
        ListTag receipts = tag.getList("reward_receipts", Tag.TAG_COMPOUND);
        for (int index = 0; index < receipts.size(); index++) {
            if (FaultInjection.shouldFail(FaultInjection.FailurePoint.SAVE_LOAD_CORRUPT_ENTRY)) {
                // Injected corruption: dropped like a malformed receipt.
                continue;
            }
            RewardReceipt receipt = RewardReceipt.load(receipts.getCompound(index));
            if (receipt != null) {
                data.putRewardReceipt(receipt);
            }
        }
        // Hard deserialization cap: a corrupt save cannot grow the receipt
        // map without bound. Eviction only ever drops the oldest CLAIMED
        // entries; a PENDING receipt is never evicted.
        while (data.rewardReceipts.size() > RewardOutboxPolicy.MAX_RECEIPTS) {
            UUID victim = data.oldestClaimedReceiptId();
            if (victim == null) {
                break;
            }
            data.rewardReceipts.remove(victim);
        }
        if (data.rewardReceipts.size() > RewardOutboxPolicy.MAX_RECEIPTS) {
            // More undelivered PENDING receipts than the cap (impossible in
            // normal play): keep every receipt instead of silently dropping
            // rewards, and park a running campaign in SAFE_MODE so the world
            // side effects pause until the save is inspected.
            LastTrain.LOGGER.error(
                    "Reward receipts exceed the cap of {} with no CLAIMED entries left to "
                            + "evict; all {} PENDING receipts were kept and the campaign "
                            + "pauses in SAFE_MODE",
                    RewardOutboxPolicy.MAX_RECEIPTS,
                    data.rewardReceipts.size());
            if (data.status == CampaignStatus.RUNNING) {
                data.status = CampaignStatus.SAFE_MODE;
            }
        }
    }

    private static MissionPoolPolicy.Entry loadHistoryEntry(CompoundTag tag) {
        MissionType type = MissionType.parse(tag.getString("type")).orElse(null);
        if (type == null) {
            return null;
        }
        String rawOutcome = tag.getString("outcome");
        for (MissionPoolPolicy.Outcome outcome : MissionPoolPolicy.Outcome.values()) {
            if (outcome.name().equals(rawOutcome)) {
                return new MissionPoolPolicy.Entry(type, tag.getBoolean("mainline"), outcome);
            }
        }
        return null;
    }

    private void saveOptionalState(CompoundTag tag, HolderLookup.Provider registries) {
        if (proposedMission != null) {
            tag.put("proposed_mission", proposedMission.save(registries));
        }
        ListTag missions = new ListTag();
        for (ActiveMission mission : optionalMissions) {
            missions.add(mission.save(registries));
        }
        tag.put("optional_missions", missions);
        ListTag receipts = new ListTag();
        rewardReceipts.values().forEach(receipt -> receipts.add(receipt.save()));
        tag.put("reward_receipts", receipts);
        ListTag cleanups = new ListTag();
        pendingSiteCleanups.forEach(cleanup -> cleanups.add(cleanup.save()));
        tag.put("pending_cleanups", cleanups);
        ListTag history = new ListTag();
        for (MissionPoolPolicy.Entry entry : missionHistory) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("type", entry.type().serializedName());
            entryTag.putBoolean("mainline", entry.mainline());
            entryTag.putString("outcome", entry.outcome().name());
            history.add(entryTag);
        }
        tag.put("mission_history", history);
        tag.put("team_members", saveUuidSet(teamMembers));
        if (captainId != null) {
            tag.putString("captain_id", captainId.toString());
        }
        tag.putLong("captain_transfer_tick", captainTransferTick);
        tag.putLong("captain_offline_since_tick", captainOfflineSinceTick);
        if (pendingTeamVote != null) {
            tag.put("pending_team_vote", saveVote(pendingTeamVote));
        }
    }

    // ------------------------------------------------------------------
    // Durable route plan state (schema 10): plan → realize two phases.
    // Pending plans, the config salt and the rolling planner cursor are
    // persisted so committed plans are adopted as-is on reload; realized
    // segments are trimmed from the pending list and never re-planned.
    // ------------------------------------------------------------------

    /**
     * The deterministic route rules version this campaign commits its plans
     * with. New campaigns start on
     * {@link RouteSegmentPlanner#DEFAULT_ROUTE_RULES_VERSION}; saves without
     * the key are old worlds and keep version 1 until
     * {@link #adoptRouteRulesVersion} is called explicitly.
     */
    public int routeRulesVersion() {
        return routeRulesVersion;
    }

    /**
     * Plans {@code segment} forward and returns its committed plan. When the
     * committed plan list does not reach far enough (a new campaign, or
     * progress past the planned horizon), the planner extends it by at least
     * {@link #DEFAULT_ROUTE_PLAN_AHEAD} segments under the campaign's
     * {@link #routeRulesVersion} and the extension is persisted.
     *
     * <p>A campaign in {@link #legacyLinearRoute()} never engages the
     * planner: it returns the fixed legacy straight plan for every future
     * segment and persists no plan state.</p>
     *
     * @throws IllegalArgumentException when the segment was already realized
     */
    public RouteSegmentPlan routePlan(int segment) {
        if (segment < 1) {
            throw new IllegalArgumentException("Route segments start at 1");
        }
        if (segment <= generatedRouteSegment) {
            throw new IllegalArgumentException(
                    "Route segment " + segment + " was already realized and cannot be re-planned");
        }
        if (legacyLinearRoute()) {
            return legacyLinearPlan(segment);
        }
        RouteSegmentPlanner planner = routePlanner();
        int target = Math.min(
                MAX_ROUTE_SEGMENT,
                Math.max(segment, generatedRouteSegment + DEFAULT_ROUTE_PLAN_AHEAD));
        if (planner.lastPlannedSegment() < target) {
            planner.plan(target);
            persistRoutePlanState();
        }
        return planner.plan(segment);
    }

    /**
     * True while this campaign keeps the pre-planner legacy linear
     * production behavior: route rules version 1 with no committed plan state
     * at all. Future segments stay the fixed 64-block eastbound straight
     * corridor with the every-fourth-segment waypoint platform, and the
     * multi-template planner is never engaged. The explicit
     * {@link #adoptRouteRulesVersion} migration to version 2 exits this mode;
     * committed version-1 plans always win over the legacy fallback, and
     * already-realized segments are never touched.
     */
    public boolean legacyLinearRoute() {
        return routeRulesVersion <= 1
                && routePlans.isEmpty()
                && routePlanCursor.nextSegment() <= 1;
    }

    /**
     * The fixed legacy production plan: a plain eastbound straight corridor
     * with no interest points. The layout derives the historical
     * every-fourth-segment waypoint platform from the straight template.
     */
    private static RouteSegmentPlan legacyLinearPlan(int segment) {
        return new RouteSegmentPlan(
                segment,
                0L,
                SegmentTemplate.STRAIGHT,
                List.of(),
                List.of(new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH)));
    }

    /** Pending (planned, not yet realized) route plans, in segment order. */
    public List<RouteSegmentPlan> plannedRouteSegments() {
        return List.copyOf(routePlans);
    }

    /**
     * Trims the persisted pending plan list after segment {@code segment} was
     * realized. The planner keeps its memoized copy for the session; only the
     * durable list is trimmed.
     */
    public void dropRoutePlanThrough(int segment) {
        if (routePlans.removeIf(plan -> plan.segmentIndex() <= segment)) {
            setDirty();
        }
    }

    /**
     * Replaces the entire committed plan state with externally produced plans
     * (test and content tooling entry point). Only valid before any route
     * segment has been realized: the plans must be ordered, contiguous and
     * start at segment 1.
     */
    public void commitRoutePlans(List<RouteSegmentPlan> plans) {
        Objects.requireNonNull(plans, "plans");
        if (generatedRouteSegment > 0) {
            throw new IllegalStateException(
                    "Route plans can only be committed before any segment is realized");
        }
        int expected = 1;
        for (RouteSegmentPlan plan : plans) {
            if (plan == null || plan.segmentIndex() != expected) {
                throw new IllegalArgumentException(
                        "Committed route plans must be contiguous from segment 1, expected "
                                + expected);
            }
            expected++;
        }
        routePlans.clear();
        routePlans.addAll(plans);
        routePlanCursor = deriveCursor(plans);
        routePlanner = null;
        setDirty();
    }

    /**
     * Explicitly adopts a newer route rules version. Committed pending plans
     * stay; only segments planned afterwards use the new version, so an
     * existing world never silently re-rolls its committed route. Adopting
     * version 2 also exits the {@link #legacyLinearRoute()} mode: segments
     * after the migration follow the multi-template planner while the
     * already-realized segments stay untouched.
     */
    public boolean adoptRouteRulesVersion(int version) {
        if (version < 1 || version == routeRulesVersion) {
            return false;
        }
        routeRulesVersion = version;
        routePlanner = null;
        setDirty();
        return true;
    }

    private RouteSegmentPlanner routePlanner() {
        if (routePlanner != null) {
            return routePlanner;
        }
        if (routePlans.isEmpty() && routePlanCursor.nextSegment() <= 1) {
            // No committed plan state yet: a fresh campaign. (A pre-planner
            // legacy world never reaches the planner — routePlan keeps it on
            // the fixed legacy linear production until the explicit
            // migration.)
            routePlanner = new RouteSegmentPlanner(
                    campaignSeed,
                    RouteSegmentPlanner.DEFAULT_ROUTE_INDEX,
                    routeRulesVersion,
                    RouteTemplateConfig.DEFAULT);
            return routePlanner;
        }
        try {
            routePlanner = RouteSegmentPlanner.resume(
                    campaignSeed,
                    RouteSegmentPlanner.DEFAULT_ROUTE_INDEX,
                    routeRulesVersion,
                    RouteTemplateConfig.DEFAULT,
                    generatedRouteSegment,
                    routePlanCursor,
                    routePlans);
        } catch (IllegalArgumentException inconsistent) {
            LastTrain.LOGGER.warn(
                    "Route plan state is inconsistent ({}); future segments are re-derived "
                            + "deterministically from the campaign seed",
                    inconsistent.getMessage());
            routePlans.clear();
            routePlanCursor = RouteSegmentPlanner.Cursor.fresh();
            routePlanner = new RouteSegmentPlanner(
                    campaignSeed,
                    RouteSegmentPlanner.DEFAULT_ROUTE_INDEX,
                    routeRulesVersion,
                    RouteTemplateConfig.DEFAULT);
        }
        return routePlanner;
    }

    private void persistRoutePlanState() {
        RouteSegmentPlanner planner = routePlanner;
        if (planner == null) {
            return;
        }
        routePlans.clear();
        routePlans.addAll(planner.plansAbove(generatedRouteSegment));
        routePlanCursor = planner.cursor();
        RouteTemplateConfig.SeedSalt salt = RouteTemplateConfig.DEFAULT.seedSalt();
        if ((routePlanConfigSaltLo != 0L || routePlanConfigSaltHi != 0L)
                && (routePlanConfigSaltLo != salt.lo() || routePlanConfigSaltHi != salt.hi())) {
            // Committed pending plans stay; the new config digest only enters
            // the seed of segments planned from now on.
            LastTrain.LOGGER.warn(
                    "Route template config changed since the last plan commit; future route "
                            + "segments re-roll under the new config digest");
        }
        routePlanConfigSaltLo = salt.lo();
        routePlanConfigSaltHi = salt.hi();
        setDirty();
    }

    private static RouteSegmentPlanner.Cursor deriveCursor(List<RouteSegmentPlan> plans) {
        int nextSegment = 1;
        int lastStation = 1;
        int lastCity = 0;
        List<Integer> bridges = new ArrayList<>();
        for (RouteSegmentPlan plan : plans) {
            nextSegment = Math.max(nextSegment, plan.segmentIndex() + 1);
            switch (plan.template()) {
                case STATION -> lastStation = Math.max(lastStation, plan.segmentIndex());
                case CITY_BYPASS -> lastCity = Math.max(lastCity, plan.segmentIndex());
                case BRIDGE_TUNNEL -> bridges.add(plan.segmentIndex());
                default -> {
                }
            }
        }
        int window = RouteTemplateConfig.DEFAULT.bridgeTunnelWindow();
        int next = nextSegment;
        bridges.removeIf(bridge -> bridge <= next - 1 - window);
        return new RouteSegmentPlanner.Cursor(nextSegment, lastStation, lastCity, bridges);
    }

    private static void loadRoutePlanState(CompoundTag tag, CampaignSavedData data) {
        CompoundTag state = tag.getCompound("route_plan_state");
        if (state.isEmpty()) {
            return;
        }
        data.routePlanConfigSaltLo = state.getLong("config_salt_lo");
        data.routePlanConfigSaltHi = state.getLong("config_salt_hi");
        CompoundTag cursorTag = state.getCompound("cursor");
        int nextSegment = Math.max(1, cursorTag.getInt("next_segment"));
        int lastStation = cursorTag.contains("last_station_segment")
                ? Math.max(1, cursorTag.getInt("last_station_segment"))
                : 1;
        int lastCity = Math.max(0, cursorTag.getInt("last_city_bypass_segment"));
        List<Integer> bridges = new ArrayList<>();
        for (Tag entry : cursorTag.getList("recent_bridges", Tag.TAG_INT)) {
            int bridge = ((IntTag) entry).getAsInt();
            if (bridge >= 1 && bridge < nextSegment) {
                bridges.add(bridge);
            }
        }
        data.routePlanCursor = new RouteSegmentPlanner.Cursor(nextSegment, lastStation, lastCity, bridges);
        ListTag plans = state.getList("pending_plans", Tag.TAG_COMPOUND);
        for (int index = 0; index < plans.size() && data.routePlans.size() < MAX_ROUTE_PLANS_ON_LOAD; index++) {
            RouteSegmentPlan plan = RouteSegmentPlan.load(plans.getCompound(index));
            if (plan != null) {
                data.routePlans.add(plan);
            }
        }
    }

    private void saveRoutePlanState(CompoundTag tag) {
        CompoundTag state = new CompoundTag();
        state.putLong("config_salt_lo", routePlanConfigSaltLo);
        state.putLong("config_salt_hi", routePlanConfigSaltHi);
        CompoundTag cursorTag = new CompoundTag();
        cursorTag.putInt("next_segment", routePlanCursor.nextSegment());
        cursorTag.putInt("last_station_segment", routePlanCursor.lastStationSegment());
        cursorTag.putInt("last_city_bypass_segment", routePlanCursor.lastCityBypassSegment());
        ListTag bridges = new ListTag();
        routePlanCursor.recentBridges().forEach(bridge -> bridges.add(IntTag.valueOf(bridge)));
        cursorTag.put("recent_bridges", bridges);
        state.put("cursor", cursorTag);
        ListTag plans = new ListTag();
        routePlans.forEach(plan -> plans.add(plan.save()));
        state.put("pending_plans", plans);
        tag.put("route_plan_state", state);
    }

    private static CompoundTag saveVote(TeamPermissionPolicy.Vote vote) {
        CompoundTag tag = new CompoundTag();
        tag.putString("operation", vote.operation().serializedName());
        tag.putString("initiated_by", vote.initiatedBy().toString());
        tag.putLong("started_at_tick", vote.startedAtTick());
        tag.putLong("expires_at_tick", vote.expiresAtTick());
        tag.put("yes_votes", saveUuidSet(vote.yesVotes()));
        tag.put("no_votes", saveUuidSet(vote.noVotes()));
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

    private void migrateFinaleState(int loadedSchema) {
        FinalePolicy.MigratedState migrated = FinalePolicy.migrate(
                mode,
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
        if (mode == CampaignMode.ENDLESS) {
            // A mode switch keeps the completed finale receipt for history,
            // but releases the story-only hub reservation and timer gates.
            finalDayElapsed = false;
            finaleHubRouteSegment = 0;
            if (isFinaleMission(activeMission)) {
                activeMission = null;
                activeKeyMission = null;
            }
        }
        schemaVersion = CURRENT_SCHEMA;
    }

    private static int maxDay(CampaignMode mode) {
        return mode == CampaignMode.ENDLESS ? Integer.MAX_VALUE : FINAL_DAY;
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
        registerGunfire(true);
    }

    /** 事件接线传入实时在线状态，避免无人服务器中的模组事件偷跑进度。 */
    public void registerGunfire(boolean hasActivePlayers) {
        if (status != CampaignStatus.RUNNING || !hasActivePlayers) {
            return;
        }
        attention = PursuitPolicy.afterGunfireAttention(attention);
        pursuitDistance = PursuitPolicy.afterGunfirePursuit(pursuitDistance);
        applyInfectionEvent(InfectionPolicy.Event.GUNFIRE);
        setDirty();
    }

    public void registerExplosion() {
        registerExplosion(true);
    }

    public void registerExplosion(boolean hasActivePlayers) {
        if (status != CampaignStatus.RUNNING || !hasActivePlayers) {
            return;
        }
        attention = PursuitPolicy.afterExplosionAttention(attention);
        pursuitDistance = PursuitPolicy.afterExplosionPursuit(pursuitDistance);
        applyInfectionEvent(InfectionPolicy.Event.EXPLOSION);
        setDirty();
    }

    /** 公共尸潮事件入口；当前由抽象后方尸潮追上列车时调用。 */
    public void registerHorde() {
        registerHorde(true);
    }

    public void registerHorde(boolean hasActivePlayers) {
        if (status != CampaignStatus.RUNNING || !hasActivePlayers) {
            return;
        }
        applyInfectionEvent(InfectionPolicy.Event.HORDE);
        setDirty();
    }

    private void updateInfectionProgress(boolean hasActivePlayers) {
        InfectionPolicy.Sample previous = infectionSample();
        InfectionPolicy.Sample next = InfectionPolicy.sample(
                previous,
                pursuitDistance,
                hasActivePlayers);
        if (next.equals(previous)) {
            return;
        }
        infectionStage = next.stageIndex();
        infectionTicks = next.infectionTicks();
        // 与现有 totalActiveTicks 一样按低频检查点标脏；阶段跃迁必须立即落盘。
        if (next.stage() != previous.stage()
                || next.infectionTicks() / 200L != previous.infectionTicks() / 200L) {
            setDirty();
        }
    }

    private void applyInfectionEvent(InfectionPolicy.Event event) {
        InfectionPolicy.Sample next = InfectionPolicy.onEvent(
                infectionSample(),
                event,
                true);
        applyInfectionSample(next);
    }

    private void applyInfectionSample(InfectionPolicy.Sample next) {
        infectionStage = next.stageIndex();
        infectionTicks = next.infectionTicks();
    }

    private TickOutcome updatePursuitPressure() {
        if (totalActiveTicks % PursuitPolicy.SAMPLE_INTERVAL_TICKS != 0L) {
            return TickOutcome.NONE;
        }

        PursuitPolicy.Sample next = PursuitPolicy.sample(
                mode,
                attention,
                pursuitDistance,
                routeSegment,
                lastPursuitRouteSegment,
                day,
                infectionSample().stage());
        if (next.attention() != attention
                || next.pursuitDistance() != pursuitDistance
                || next.routeSegment() != lastPursuitRouteSegment) {
            attention = next.attention();
            pursuitDistance = next.pursuitDistance();
            lastPursuitRouteSegment = next.routeSegment();
            setDirty();
        }

        if (activeTicksIntoDay >= activeTicksPerDay
                || !PursuitPolicy.shouldTriggerSiege(
                        mode,
                        status,
                        day,
                        next.pursuitDistance(),
                        activeMission != null)) {
            return TickOutcome.NONE;
        }

        // 先预览本次尸潮事件可能跨越的感染阈值，让新围攻立即采用新阶段强度；
        // 只有任务成功创建后才提交事件，避免失败重试重复累计。
        InfectionPolicy.Sample hordeInfection = InfectionPolicy.afterHorde(
                infectionSample(),
                true);
        if (!createMission(
                MissionType.ZOMBIE_BLOCKADE,
                effectivePlayers,
                hordeInfection.stage())) {
            return TickOutcome.NONE;
        }

        // The active blockade is the pressure valve. The abstract horde stays
        // at zero distance until turn-in or fallback restores it; the active
        // mission prevents a second siege while the first is unresolved.
        lastPursuitRouteSegment = routeSegment;
        applyInfectionSample(hordeInfection);
        setDirty();
        return TickOutcome.SIEGE_TRIGGERED;
    }

    /**
     * Starts the campaign and assigns the first starter to captain. The UUID
     * is supplied by the logical server rather than trusted from a client
     * payload; the no-argument overload remains for old callers/tests that
     * start a campaign before any player has joined.
     */
    public boolean start(UUID captain) {
        if (status != CampaignStatus.NOT_STARTED) {
            return false;
        }
        if (captain != null) {
            if (!teamMembers.contains(captain)) {
                if (teamMembers.size() >= MAX_TEAM_MEMBERS) {
                    return false;
                }
                teamMembers.add(captain);
            }
            // The UUID passed by the server is the player who actually
            // started this campaign. It is authoritative even when a player
            // had joined the world before the start command was issued.
            captainId = captain;
            captainOnline = true;
            captainOfflineSinceTick = -1L;
        }
        status = CampaignStatus.RUNNING;
        setDirty();
        return true;
    }

    public boolean start() {
        return start(null);
    }

    /**
     * Converts a completed story save to the post-victory endless pacing
     * model. World ownership and all durable mission/team state remain in
     * place; only story-only timing gates are released.
     */
    public boolean enableEndlessMode() {
        if (mode != CampaignMode.STORY_100_DAYS
                || status != CampaignStatus.COMPLETED) {
            return false;
        }
        mode = CampaignMode.ENDLESS;
        status = CampaignStatus.RUNNING;
        finalDayElapsed = false;
        finaleHubRouteSegment = 0;
        activeKeyMission = null;
        setDirty();
        return true;
    }

    public TickOutcome tick() {
        return tick(effectivePlayers);
    }

    public TickOutcome tick(int activePlayers) {
        if (status != CampaignStatus.RUNNING || activePlayers <= 0) {
            return TickOutcome.NONE;
        }

        // 感染计时独立于 day，在终局日计时封顶后仍可由在线 tick 继续推进；
        // 这一步必须位于 finalDayElapsed 的提前返回之前。
        updateInfectionProgress(true);
        ensureFinaleHub();
        TickOutcome finaleOutcome = reconcileFinaleState();
        if (finaleOutcome != TickOutcome.NONE) {
            return finaleOutcome;
        }

        if (mode != CampaignMode.ENDLESS && finalDayElapsed) {
            return TickOutcome.NONE;
        }

        observeActivePlayers(activePlayers);
        totalActiveTicks++;
        activeTicksIntoDay++;
        if ((totalActiveTicks % 200L) == 0L) {
            setDirty();
        }
        TickOutcome pressureOutcome = updatePursuitPressure();
        if (activeTicksIntoDay < activeTicksPerDay) {
            return pressureOutcome != TickOutcome.NONE
                    ? pressureOutcome
                    : TickOutcome.NONE;
        }

        activeTicksIntoDay -= activeTicksPerDay;
        if (mode != CampaignMode.ENDLESS && day >= FINAL_DAY) {
            finalDayElapsed = true;
            activeTicksIntoDay = 0;
            setDirty();
            TickOutcome completed = reconcileFinaleState();
            return completed != TickOutcome.NONE
                    ? completed
                    : TickOutcome.FINAL_DAY_ELAPSED;
        }

        day = nextDay(day, 1, mode);
        threat = nextThreat(threat, 1, day);
        ensureFinaleHub();
        if (mode != CampaignMode.ENDLESS && day >= FINAL_DAY) {
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

        day = nextDay(day, amount, mode);
        threat = nextThreat(threat, amount, day);
        activeTicksIntoDay = 0;
        ensureFinaleHub();
        if (mode == CampaignMode.ENDLESS || day < FINAL_DAY) {
            tryGenerateDailyMission();
        }
        setDirty();
    }

    // ------------------------------------------------------------------
    // Test-only accelerated clock. These entries are memory-only, gated
    // behind an explicit opt-in and never called by the production server
    // tick loop; see FastForwardMode for the supported entry points and the
    // equivalence contract with tick-by-tick progression.
    // ------------------------------------------------------------------

    int activeTicksPerDay() {
        return activeTicksPerDay;
    }

    boolean fastForwardEnabled() {
        return fastForwardEnabled;
    }

    void enableFastForward(int ticksPerDay) {
        if (ticksPerDay < FastForwardMode.MIN_TICKS_PER_DAY) {
            throw new IllegalArgumentException(
                    "Fast-forward day length must be >= "
                            + FastForwardMode.MIN_TICKS_PER_DAY
                            + " active ticks: "
                            + ticksPerDay);
        }
        activeTicksPerDay = ticksPerDay;
        fastForwardEnabled = true;
    }

    void disableFastForward() {
        activeTicksPerDay = DEFAULT_ACTIVE_TICKS_PER_DAY;
        fastForwardEnabled = false;
    }

    private void requireFastForwardEnabled() {
        if (!fastForwardEnabled) {
            throw new IllegalStateException(
                    "Fast-forward mode is disabled. It is a test-only entry "
                            + "point: enable it explicitly via "
                            + "FastForwardMode.enable(data, ticksPerDay) first; "
                            + "the production server never calls it.");
        }
    }

    /**
     * Test-only batch clock: runs {@code ticks} ordinary {@link #tick()}s
     * back to back on the logical server thread. Requires the explicit
     * fast-forward opt-in; with it disabled this entry throws.
     */
    FastForwardMode.AdvanceSummary advanceActiveTicks(long ticks) {
        requireFastForwardEnabled();
        int startDay = day;
        long processed = 0L;
        TickOutcome lastOutcome = TickOutcome.NONE;
        while (processed < ticks) {
            lastOutcome = tick();
            processed++;
        }
        return new FastForwardMode.AdvanceSummary(
                processed,
                (long) day - startDay,
                day,
                lastOutcome);
    }

    /**
     * Test-only equivalent of "all ticks of one day executed in order":
     * exactly {@link #activeTicksPerDay()} ticks. Equivalence with an
     * explicit tick-by-tick loop is enforced by FastForwardModeTest.
     */
    FastForwardMode.AdvanceSummary simulateDay() {
        requireFastForwardEnabled();
        return advanceActiveTicks(activeTicksPerDay);
    }

    public boolean advanceRoute(int amount) {
        if (amount <= 0
                || status != CampaignStatus.RUNNING
                || routeSegment >= MAX_ROUTE_SEGMENT) {
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
        if (segment <= generatedRouteSegment || segment > MAX_ROUTE_SEGMENT) {
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

    private static int nextDay(int currentDay, int amount, CampaignMode mode) {
        long candidate = (long) Math.max(1, currentDay) + Math.max(0, amount);
        return (int) Math.min(maxDay(mode), candidate);
    }

    private static int nextThreat(int currentThreat, int days, int currentDay) {
        long next = (long) Math.max(0, currentThreat)
                + Math.max(0, days)
                + Math.max(1, currentDay) / 20L;
        return (int) Math.min(100L, next);
    }

    public boolean createMission(MissionType type) {
        return createMission(type, effectivePlayers);
    }

    public boolean createMission(MissionType type, int teamSize) {
        return createMission(type, teamSize, infectionSample().stage());
    }

    private boolean createMission(
            MissionType type,
            int teamSize,
            InfectionPolicy.Stage intensityStage) {
        if (type == null
                || !FinalePolicy.allowsOrdinaryMission(mode, status, day)
                || (type.blocksRoute()
                        && !FinalePolicy.allowsOrdinaryMainlineMission(mode, status, day))
                || activeMission != null
                || !type.occupiesMainlineSlot()
                || !MissionPoolPolicy.mayCreateMainline(missionHistory, type)) {
            return false;
        }
        int target = PopulationScalingPolicy.missionTarget(
                type,
                teamSize,
                intensityStage);
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
                        mode,
                        keyMission,
                        day,
                        routeSegment);
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
        UUID completedId = activeMission.id();
        MissionType completedType = activeMission.type();
        activeMission.complete();
        activeMission = null;
        activeKeyMission = null;
        recordMissionOutcome(completedType, MissionPoolPolicy.Outcome.COMPLETED);
        if (RewardOutboxPolicy.rewardedOnTurnIn(completedType)) {
            // 先把一次性收据写进权威存档，再由世界适配器尝试整批投递；主线任务
            // 因而也满足“继续前进所需资源 + 少量净收益”，且重启不会重复创建。
            putRewardReceipt(new RewardReceipt(
                    completedId,
                    completedType,
                    RewardOutboxPolicy.ReceiptState.PENDING));
        }
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
        recordMissionOutcome(failedType, MissionPoolPolicy.Outcome.FAILED);
        if (failedType == MissionType.ZOMBIE_BLOCKADE) {
            pursuitDistance = Math.max(
                    pursuitDistance,
                    PursuitPolicy.PURSUIT_AFTER_SIEGE_FALLBACK);
        }
        setDirty();
        return true;
    }

    /**
     * Director-side proposal of an optional mission. Proposals never occupy
     * the mainline slot, never block route progress, and carry an absolute
     * tick deadline captured from the current active-tick counter.
     */
    public boolean proposeOptionalMission(MissionType type) {
        if (!FinalePolicy.allowsOrdinaryMission(mode, status, day)
                || proposedMission != null
                || activeOptionalCount() >= OptionalMissionPolicy.MAX_ACTIVE_OPTIONAL_MISSIONS
                || !OptionalMissionPolicy.isOptional(type)) {
            return false;
        }
        proposedMission =
                ActiveMission.createProposal(type, day, routeSegment, totalActiveTicks);
        setDirty();
        return true;
    }

    /**
     * Authoritative accept/reject of the current proposal. The caller may
     * pass the task id and/or revision shown by an earlier status command;
     * both are compared against the live proposal right before the change so
     * a stale UI or a concurrent answer cannot affect the wrong task.
     */
    public ProposalAnswer acceptProposal(UUID givenId, Integer givenRevision) {
        ProposalAnswer check = checkProposal(givenId, givenRevision);
        if (check != ProposalAnswer.OK_FOR_APPLY) {
            return check;
        }
        if (activeOptionalCount() >= OptionalMissionPolicy.MAX_ACTIVE_OPTIONAL_MISSIONS) {
            return ProposalAnswer.SLOTS_FULL;
        }
        ActiveMission proposal = proposedMission;
        proposal.transitionTo(MissionStage.ACTIVE);
        optionalMissions.add(proposal);
        proposedMission = null;
        setDirty();
        return ProposalAnswer.ACCEPTED;
    }

    public ProposalAnswer rejectProposal(UUID givenId, Integer givenRevision) {
        ProposalAnswer check = checkProposal(givenId, givenRevision);
        if (check != ProposalAnswer.OK_FOR_APPLY) {
            return check;
        }
        ActiveMission proposal = proposedMission;
        proposal.transitionTo(MissionStage.SKIPPED);
        proposedMission = null;
        recordMissionOutcome(proposal.type(), MissionPoolPolicy.Outcome.SKIPPED);
        setDirty();
        return ProposalAnswer.SKIPPED;
    }

    private ProposalAnswer checkProposal(UUID givenId, Integer givenRevision) {
        if (proposedMission == null) {
            return ProposalAnswer.NOT_FOUND;
        }
        if (proposedMission.stage() != MissionStage.PROPOSED) {
            return ProposalAnswer.WRONG_STAGE;
        }
        if (givenId != null && !givenId.equals(proposedMission.id())) {
            return ProposalAnswer.STALE_ID;
        }
        if (givenRevision != null && givenRevision != proposedMission.revision()) {
            return ProposalAnswer.STALE_REVISION;
        }
        return ProposalAnswer.OK_FOR_APPLY;
    }

    /**
     * Abandons an active optional mission. Settles state first, without any
     * world access, and queues an idempotent site cleanup that runs when the
     * site chunk loads again — an unloaded chunk can never strand the slot.
     */
    public OptionalSkipResult skipOptionalMission(UUID givenId, Integer givenRevision) {
        ActiveMission target = null;
        if (givenId != null) {
            target = optionalMission(givenId).orElse(null);
            if (target == null) {
                return OptionalSkipResult.NOT_FOUND;
            }
        } else {
            List<ActiveMission> skippable = optionalMissions.stream()
                    .filter(mission -> mission.stage() == MissionStage.ACTIVE
                            || mission.stage() == MissionStage.READY_TO_TURN_IN)
                    .toList();
            if (skippable.isEmpty()) {
                return OptionalSkipResult.NOT_FOUND;
            }
            if (skippable.size() > 1) {
                return OptionalSkipResult.AMBIGUOUS;
            }
            target = skippable.get(0);
        }
        if (target.stage() != MissionStage.ACTIVE
                && target.stage() != MissionStage.READY_TO_TURN_IN) {
            return OptionalSkipResult.WRONG_STAGE;
        }
        if (givenRevision != null && givenRevision != target.revision()) {
            return OptionalSkipResult.STALE_REVISION;
        }
        target.transitionTo(MissionStage.SKIPPED);
        optionalMissions.remove(target);
        recordMissionOutcome(target.type(), MissionPoolPolicy.Outcome.SKIPPED);
        queueMissionCleanup(target);
        setDirty();
        return OptionalSkipResult.SKIPPED;
    }

    public ActiveMission proposedMission() {
        return proposedMission;
    }

    public List<ActiveMission> optionalMissions() {
        return List.copyOf(optionalMissions);
    }

    public Optional<ActiveMission> optionalMission(UUID id) {
        return optionalMissions.stream()
                .filter(mission -> mission.id().equals(id))
                .findFirst();
    }

    public boolean assignOptionalMissionSite(UUID id, BlockPos site) {
        ActiveMission mission = optionalMission(id).orElse(null);
        if (mission == null || !mission.assignSite(site)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean markOptionalMissionWorldPrepared(UUID id) {
        ActiveMission mission = optionalMission(id).orElse(null);
        if (mission == null || !mission.markWorldPrepared()) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Deduplicated salvage repair accounting, keyed by unique damage index. */
    public boolean recordSalvageRepair(UUID id, int index) {
        ActiveMission mission = optionalMission(id).orElse(null);
        if (mission == null
                || mission.type() != MissionType.SALVAGE_CAR
                || !mission.recordRepairIndex(index)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean recordSurvivorRescued(UUID id) {
        ActiveMission mission = optionalMission(id).orElse(null);
        if (mission == null
                || mission.type() != MissionType.RESCUE_SURVIVOR
                || !mission.setObservedProgress(mission.target())) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * Objective completion gate for optional missions: persists the
     * REWARD_PENDING receipt before any world mutation happens, then the
     * reward dispatcher executes the atomic crate fill and flips the receipt
     * to CLAIMED in the same tick. The receipt alone is the authority over
     * delivery: CLAIMED is terminal, PENDING retries once per tick.
     */
    public boolean completeOptionalMission(UUID id) {
        ActiveMission mission = optionalMission(id).orElse(null);
        if (mission == null || mission.stage() != MissionStage.READY_TO_TURN_IN) {
            return false;
        }
        mission.transitionTo(MissionStage.REWARD_PENDING);
        putRewardReceipt(new RewardReceipt(
                id,
                mission.type(),
                RewardOutboxPolicy.ReceiptState.PENDING));
        setDirty();
        return true;
    }

    /** Marks the outbox receipt claimed and settles the mission record. */
    public boolean markRewardClaimed(UUID id) {
        if (FaultInjection.shouldFail(FaultInjection.FailurePoint.REWARD_PERSIST)) {
            // Injected crash between the durable PENDING write and the
            // CLAIMED flip: the receipt hangs PENDING and the outbox retries
            // the same entry on its next dispatch tick.
            return false;
        }
        RewardReceipt receipt = rewardReceipts.get(id);
        if (receipt == null) {
            return false;
        }
        if (receipt.state() != RewardOutboxPolicy.ReceiptState.CLAIMED) {
            rewardReceipts.put(id, receipt.claimed());
        }
        ActiveMission mission = optionalMission(id).orElse(null);
        if (mission != null && mission.stage() == MissionStage.REWARD_PENDING) {
            mission.transitionTo(MissionStage.COMPLETED);
            optionalMissions.remove(mission);
            recordMissionOutcome(mission.type(), MissionPoolPolicy.Outcome.COMPLETED);
            queueMissionCleanup(mission);
        }
        setDirty();
        return true;
    }

    public Optional<RewardReceipt> rewardReceipt(UUID id) {
        return Optional.ofNullable(rewardReceipts.get(id));
    }

    public List<RewardReceipt> rewardReceipts() {
        return List.copyOf(rewardReceipts.values());
    }

    /**
     * Drops a CLAIMED receipt. Used by the crate marker cap so a marker
     * eviction always drops its receipt too; a PENDING receipt is never
     * dropped, not even by explicit removal.
     */
    public boolean removeRewardReceipt(UUID id) {
        RewardReceipt receipt = rewardReceipts.get(id);
        if (receipt == null || receipt.state() != RewardOutboxPolicy.ReceiptState.CLAIMED) {
            return false;
        }
        rewardReceipts.remove(id);
        setDirty();
        return true;
    }

    /**
     * Settles every expired proposal and active optional mission against the
     * monotonic tick counter. Pure state change plus cleanup records — no
     * chunk access — so timeouts settle even while the site is unloaded.
     */
    public int settleOptionalTimeouts() {
        if (FaultInjection.shouldFail(
                FaultInjection.FailurePoint.MISSION_SETTLE_CHUNK_UNLOADED)) {
            // Injected as if the site chunks were still unloaded: settlement
            // defers to a later tick and strands no state.
            return 0;
        }
        long now = totalActiveTicks;
        int settled = 0;
        for (ActiveMission mission : List.copyOf(optionalMissions)) {
            if (mission.stage() == MissionStage.REWARD_PENDING
                    || !OptionalMissionPolicy.shouldTimeout(mission.deadlineTick(), now)) {
                continue;
            }
            failOptionalMission(mission);
            settled++;
        }
        if (proposedMission != null
                && OptionalMissionPolicy.shouldTimeout(proposedMission.deadlineTick(), now)) {
            MissionType expired = proposedMission.type();
            proposedMission.transitionTo(MissionStage.SKIPPED);
            proposedMission = null;
            recordMissionOutcome(expired, MissionPoolPolicy.Outcome.SKIPPED);
            setDirty();
            settled++;
        }
        return settled;
    }

    /**
     * Day-100 finale protection: unanswered proposals are auto-rejected and
     * unfinished optional missions are settled as failed. PENDING reward
     * receipts are left alone so the outbox still delivers them.
     */
    public void settleOptionalMissionsAtFinale() {
        boolean changed = false;
        if (proposedMission != null) {
            MissionType rejected = proposedMission.type();
            proposedMission.transitionTo(MissionStage.SKIPPED);
            proposedMission = null;
            recordMissionOutcome(rejected, MissionPoolPolicy.Outcome.SKIPPED);
            changed = true;
        }
        for (ActiveMission mission : List.copyOf(optionalMissions)) {
            if (mission.stage() == MissionStage.REWARD_PENDING) {
                continue;
            }
            failOptionalMission(mission);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    private void failOptionalMission(ActiveMission mission) {
        mission.transitionTo(MissionStage.FAILED);
        optionalMissions.remove(mission);
        recordMissionOutcome(mission.type(), MissionPoolPolicy.Outcome.FAILED);
        queueMissionCleanup(mission);
    }

    public List<PendingSiteCleanup> pendingSiteCleanups() {
        return List.copyOf(pendingSiteCleanups);
    }

    public boolean completePendingCleanup(UUID id) {
        boolean removed = pendingSiteCleanups.removeIf(cleanup -> cleanup.missionId().equals(id));
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /**
     * Queues the site of a settled mission whose record is being dropped for
     * idempotent world cleanup, so clearing a slot never strands world
     * residue behind. Used by the optional settlement paths and by the day-100
     * finale clearing. The queue is bounded; the oldest entry is dropped
     * first when a corrupt save floods it, but normal operation never reaches
     * the cap.
     */
    private void queueMissionCleanup(ActiveMission mission) {
        if (mission.site() == null) {
            return;
        }
        if (pendingSiteCleanups.size() >= MAX_PENDING_CLEANUPS) {
            LastTrain.LOGGER.warn(
                    "Site cleanup queue reached its cap of {}; dropping the oldest entry",
                    MAX_PENDING_CLEANUPS);
            pendingSiteCleanups.remove(0);
        }
        pendingSiteCleanups.add(new PendingSiteCleanup(
                mission.id(),
                mission.type(),
                mission.site(),
                mission.target()));
    }

    private int activeOptionalCount() {
        return (int) optionalMissions.stream()
                .filter(mission -> !mission.stage().terminal())
                .count();
    }

    private void recordMissionOutcome(MissionType type, MissionPoolPolicy.Outcome outcome) {
        missionHistory.add(MissionPoolPolicy.entry(type, outcome));
        if (missionHistory.size() > MissionPoolPolicy.HISTORY_LIMIT) {
            missionHistory.remove(0);
        }
    }

    private void putRewardReceipt(RewardReceipt receipt) {
        if (rewardReceipts.containsKey(receipt.missionId())) {
            return;
        }
        while (rewardReceipts.size() >= RewardOutboxPolicy.MAX_RECEIPTS) {
            UUID victim = oldestClaimedReceiptId();
            if (victim == null) {
                // Every entry is PENDING: an undelivered reward is never
                // evicted, even if that briefly exceeds the cap.
                break;
            }
            rewardReceipts.remove(victim);
        }
        rewardReceipts.put(receipt.missionId(), receipt);
    }

    private UUID oldestClaimedReceiptId() {
        for (Map.Entry<UUID, RewardReceipt> entry : rewardReceipts.entrySet()) {
            if (entry.getValue().state() == RewardOutboxPolicy.ReceiptState.CLAIMED) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Team membership for command permission checks; first member is captain. */
    public boolean registerTeamMember(UUID playerId) {
        if (playerId == null
                || (teamMembers.size() >= MAX_TEAM_MEMBERS && !teamMembers.contains(playerId))) {
            return false;
        }
        if (!teamMembers.add(playerId)) {
            return false;
        }
        if (captainId == null) {
            captainId = playerId;
        }
        setDirty();
        return true;
    }

    public boolean isTeamMember(UUID playerId) {
        return playerId != null && teamMembers.contains(playerId);
    }

    public boolean isCaptain(UUID playerId) {
        return playerId != null && playerId.equals(captainId);
    }

    /** Current captain UUID, or {@code null} when a legacy/empty team has no captain. */
    public UUID captainId() {
        return captainId;
    }

    public Set<UUID> teamMembers() {
        return Set.copyOf(teamMembers);
    }

    public long captainTransferTick() {
        return captainTransferTick;
    }

    public long captainOfflineSinceTick() {
        return captainOfflineSinceTick;
    }

    public boolean captainOnline() {
        return captainOnline;
    }

    /**
     * Records the server's latest captain presence observation. The time
     * source is supplied by the server event layer, so unit tests can use an
     * arbitrary monotonic tick sequence.
     */
    public boolean observeCaptainOnline(boolean online, long currentTick) {
        if (captainId == null) {
            return false;
        }
        long safeTick = Math.max(0L, currentTick);
        if (online) {
            if (!captainOnline || captainOfflineSinceTick >= 0L) {
                captainOnline = true;
                captainOfflineSinceTick = -1L;
                setDirty();
                return true;
            }
            return false;
        }

        long offlineSince = captainOfflineSinceTick;
        if (offlineSince < 0L || safeTick < offlineSince) {
            offlineSince = safeTick;
        }
        if (captainOnline || captainOfflineSinceTick != offlineSince) {
            captainOnline = false;
            captainOfflineSinceTick = offlineSince;
            setDirty();
            return true;
        }
        return false;
    }

    /** Transfers captaincy without changing team membership. */
    public boolean transferCaptain(UUID requester, UUID target, long currentTick) {
        return transferCaptain(requester, target, currentTick, true);
    }

    /** Transfer variant used when the command target is an offline UUID. */
    public boolean transferCaptain(
            UUID requester,
            UUID target,
            long currentTick,
            boolean targetOnline) {
        if (!isCaptain(requester)
                || target == null
                || requester.equals(target)
                || !isTeamMember(target)) {
            return false;
        }
        captainId = target;
        captainTransferTick = Math.max(0L, currentTick);
        captainOnline = targetOnline;
        captainOfflineSinceTick = targetOnline ? -1L : Math.max(0L, currentTick);
        setDirty();
        return true;
    }

    /** Claims captaincy after the configured offline interval has elapsed. */
    public boolean claimCaptain(UUID requester, long currentTick) {
        return claimCaptain(requester, currentTick, TeamPermissionPolicy.Config.defaults());
    }

    public boolean claimCaptain(
            UUID requester,
            long currentTick,
            TeamPermissionPolicy.Config config) {
        if (config == null) {
            return false;
        }
        TeamPermissionPolicy.Requester actor =
                new TeamPermissionPolicy.Requester(requester, true, false, 0);
        TeamPermissionPolicy.TeamState team =
                new TeamPermissionPolicy.TeamState(captainId, teamMembers, captainOnline);
        if (!TeamPermissionPolicy.canClaimCaptain(
                actor,
                team,
                captainOfflineSinceTick,
                currentTick,
                config.captainClaimThresholdTicks())) {
            return false;
        }
        captainId = requester;
        captainTransferTick = Math.max(0L, currentTick);
        captainOnline = true;
        captainOfflineSinceTick = -1L;
        setDirty();
        return true;
    }

    public Optional<TeamPermissionPolicy.Vote> pendingVote() {
        return Optional.ofNullable(pendingTeamVote);
    }

    /** Starts a vote as the current captain using the safe default config. */
    public TeamPermissionPolicy.VoteResult startVote(
            TeamPermissionPolicy.Operation operation,
            UUID requester,
            long currentTick) {
        return startVote(
                operation,
                requester,
                0,
                true,
                false,
                currentTick,
                TeamPermissionPolicy.Config.defaults());
    }

    public TeamPermissionPolicy.VoteResult startVote(
            TeamPermissionPolicy.Operation operation,
            UUID requester,
            long currentTick,
            TeamPermissionPolicy.Config config) {
        return startVote(
                operation,
                requester,
                0,
                true,
                false,
                currentTick,
                config);
    }

    /** Full command-facing start path, including source/spectator/admin facts. */
    public TeamPermissionPolicy.VoteResult startVote(
            TeamPermissionPolicy.Operation operation,
            UUID requester,
            int permissionLevel,
            boolean playerSource,
            boolean spectator,
            long currentTick,
            TeamPermissionPolicy.Config config) {
        if (operation == null || config == null || !config.allowsVote(operation)) {
            return TeamPermissionPolicy.VoteResult.INVALID_OPERATION;
        }
        if (pendingTeamVote != null) {
            if (pendingTeamVote.expiredAt(currentTick)) {
                pendingTeamVote = null;
                setDirty();
            } else {
                return TeamPermissionPolicy.VoteResult.ALREADY_PENDING;
            }
        }
        TeamPermissionPolicy.Requester actor = new TeamPermissionPolicy.Requester(
                requester,
                playerSource,
                spectator,
                permissionLevel);
        TeamPermissionPolicy.TeamState team =
                new TeamPermissionPolicy.TeamState(captainId, teamMembers, captainOnline);
        if (!TeamPermissionPolicy.canInitiateVote(operation, actor, team, config)) {
            return TeamPermissionPolicy.VoteResult.NOT_ALLOWED;
        }
        long safeTick = Math.max(0L, currentTick);
        long duration = config.voteDurationTicks();
        long expiresAt = safeTick > Long.MAX_VALUE - duration
                ? Long.MAX_VALUE
                : safeTick + duration;
        pendingTeamVote = new TeamPermissionPolicy.Vote(
                operation,
                requester,
                safeTick,
                expiresAt,
                Set.of(),
                Set.of());
        setDirty();
        return TeamPermissionPolicy.VoteResult.STARTED;
    }

    public TeamPermissionPolicy.VoteResult castVote(
            UUID voter,
            boolean yes,
            long currentTick) {
        if (pendingTeamVote == null) {
            return TeamPermissionPolicy.VoteResult.NO_ACTIVE_VOTE;
        }
        if (pendingTeamVote.expiredAt(currentTick)) {
            pendingTeamVote = null;
            setDirty();
            return TeamPermissionPolicy.VoteResult.EXPIRED;
        }
        if (voter == null || !isTeamMember(voter)) {
            return TeamPermissionPolicy.VoteResult.NOT_ALLOWED;
        }
        if (pendingTeamVote.hasVoted(voter)) {
            return TeamPermissionPolicy.VoteResult.ALREADY_VOTED;
        }
        TeamPermissionPolicy.Vote next = pendingTeamVote.cast(voter, yes);
        if (TeamPermissionPolicy.votePassed(next, teamMembers)) {
            pendingTeamVote = null;
            setDirty();
            return TeamPermissionPolicy.VoteResult.PASSED;
        }
        if (TeamPermissionPolicy.voteRejected(next, teamMembers)) {
            pendingTeamVote = null;
            setDirty();
            return TeamPermissionPolicy.VoteResult.REJECTED;
        }
        pendingTeamVote = next;
        setDirty();
        return TeamPermissionPolicy.VoteResult.VOTE_RECORDED;
    }

    public TeamPermissionPolicy.VoteResult expirePendingVote(long currentTick) {
        if (pendingTeamVote == null) {
            return TeamPermissionPolicy.VoteResult.NO_ACTIVE_VOTE;
        }
        if (!pendingTeamVote.expiredAt(currentTick)) {
            return TeamPermissionPolicy.VoteResult.PENDING;
        }
        pendingTeamVote = null;
        setDirty();
        return TeamPermissionPolicy.VoteResult.EXPIRED;
    }

    public Optional<MissionType> lastMainMissionType() {
        return MissionPoolPolicy.lastMainlineType(missionHistory);
    }

    public int consecutiveFailures() {
        return MissionPoolPolicy.consecutiveFailures(missionHistory);
    }

    public List<MissionPoolPolicy.Entry> missionHistory() {
        return List.copyOf(missionHistory);
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
        // An injected backend defect is fed in as "vehicle stack not loaded",
        // so the ordinary TrainRecoveryPolicy path parks the campaign in
        // SAFE_MODE instead of mutating the world without a backend.
        boolean stackLoaded = !FaultInjection.shouldFail(
                FaultInjection.FailurePoint.VEHICLE_STACK_UNAVAILABLE)
                && vehicleStackLoaded;
        TrainRecoveryPolicy.TrainSituation situation = new TrainRecoveryPolicy.TrainSituation(
                stackLoaded,
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
     * The farthest verified segment a rescue may return the train to. Only an
     * ACTIVE route-blocking mainline mission is a hard checkpoint — the same
     * criterion the route director applies — so SUPPORT, OPTIONAL and
     * PROPOSED missions never restrict where a rescue may land. Rescue is
     * repair, not a way to skip the blocker.
     */
    public int rescueAnchorSegment() {
        ActiveMission mission = activeMission;
        Integer checkpoint = mission != null
                && mission.stage() == MissionStage.ACTIVE
                && mission.type().blocksRoute()
                ? mission.routeSegment()
                : null;
        return TrainRecoveryPolicy.anchorSegment(routeSegment, checkpoint);
    }

    private boolean tryGenerateDailyMission() {
        if (!FinalePolicy.allowsOrdinaryMission(mode, status, day)
                || activeMission != null
                || day < 2) {
            return false;
        }

        if (tryGenerateNextKeyMission()) {
            return true;
        }

        SplittableRandom random = missionRandom(0x444159L);
        boolean any = false;
        InfectionPolicy.Stage stage = infectionSample().stage();
        double chance = Math.min(
                0.85D,
                InfectionPolicy.eventChance(0.30D + day * 0.004D, stage));
        if (random.nextDouble() < chance) {
            any = MissionPoolPolicy.selectMainMissionType(missionHistory, random)
                    .map(this::createMission)
                    .orElse(false);
        }
        if (random.nextDouble() < InfectionPolicy.eventChance(0.45D, stage)) {
            any |= MissionPoolPolicy.selectOptionalType(random)
                    .map(this::proposeOptionalMission)
                    .orElse(false);
        }
        return any;
    }

    private boolean tryGenerateRouteMission() {
        if (!FinalePolicy.allowsOrdinaryMission(mode, status, day)
                || activeMission != null
                || routeSegment < 1) {
            return false;
        }

        if (tryGenerateNextKeyMission()) {
            return true;
        }

        SplittableRandom random = missionRandom(0x524f555445L ^ routeSegment);
        double routeEventChance = InfectionPolicy.eventChance(
                0.35D,
                infectionSample().stage());
        if (routeSegment % 3 != 0 && random.nextDouble() >= routeEventChance) {
            return false;
        }
        return createPacedMission(random);
    }

    private boolean tryGenerateNextKeyMission() {
        return CampaignPacingPolicy.nextKeyMission(
                        mode,
                        day,
                        routeSegment,
                        scheduledKeyMissions)
                .map(this::createKeyMission)
                .orElse(false);
    }

    private boolean createPacedMission(SplittableRandom random) {
        MissionType type = CampaignPacingPolicy.selectMissionType(
                random,
                mode,
                day,
                routeSegment);
        if (!FinalePolicy.allowsOrdinaryMainlineMission(mode, status, day)
                && type.blocksRoute()) {
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
        if (mode == CampaignMode.ENDLESS) {
            return TickOutcome.NONE;
        }
        ensureFinaleHub();
        if (day >= FINAL_DAY
                && activeMission != null
                && !isFinaleMission(activeMission)
                && !activeMission.type().blocksRoute()) {
            // Non-blocking content (support supplies, stray optional work) is
            // not allowed to consume the final task slot once the finale day
            // opens. Clearing the slot must not strand its world site: any
            // assigned site is registered for deferred cleanup before the
            // mission record is dropped.
            queueMissionCleanup(activeMission);
            activeMission = null;
            activeKeyMission = null;
            setDirty();
        }
        FinalePolicy.Directive directive = FinalePolicy.nextDirective(
                mode,
                status,
                day,
                finalDayElapsed,
                finaleMissionCompleted,
                activeMission != null);
        return switch (directive) {
            case NONE -> TickOutcome.NONE;
            case CREATE_FINALE_MISSION -> {
                // Day-100 protection: the finale can never be blocked by an
                // unanswered optional proposal, and unfinished optional
                // missions are settled before the finale starts.
                settleOptionalMissionsAtFinale();
                activeMission = ActiveMission.create(
                        ensureFinaleMissionId(),
                        MissionType.ZOMBIE_BLOCKADE,
                        FINAL_DAY,
                        finaleHubRouteSegment,
                        PopulationScalingPolicy.missionTarget(
                                MissionType.ZOMBIE_BLOCKADE,
                                effectivePlayers,
                                infectionSample().stage()));
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
        if (!FinalePolicy.finaleHubWindowOpen(mode, status, day)) {
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

    /**
     * The campaign's persistent planning seed: the mixed world seed, stored
     * in the save and used by the route planner and mission draws.
     */
    public long campaignSeed() {
        return campaignSeed;
    }

    public CampaignStatus status() {
        return status;
    }

    public CampaignMode mode() {
        return mode;
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
        return CampaignPacingPolicy.expectedRouteSegment(mode, day);
    }

    public CampaignPacingPolicy.MissionWeights pacingWeights() {
        return CampaignPacingPolicy.missionWeights(mode, day, routeSegment);
    }

    public int generatedRouteSegment() {
        return generatedRouteSegment;
    }

    public boolean routeSafetyLimitReached() {
        return RouteProgressPolicy.isAtSafetyLimit(routeSegment, MAX_ROUTE_SEGMENT);
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

    public int infectionStage() {
        return infectionStage;
    }

    public long infectionTicks() {
        return infectionTicks;
    }

    /** 为关注度、围攻、事件导演和外部同步提供同一份只读阶段采样。 */
    public InfectionPolicy.Sample infectionSample() {
        return InfectionPolicy.snapshot(infectionStage, infectionTicks);
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

    /**
     * Durable reward outbox entry. PENDING is persisted before the world
     * mutation runs; the dispatcher flips it to CLAIMED after the atomic
     * crate fill. The receipt is the sole authority over delivery: a CLAIMED
     * receipt is never re-granted, whatever the world side looks like.
     */
    public record RewardReceipt(
            UUID missionId,
            MissionType type,
            RewardOutboxPolicy.ReceiptState state) {
        public RewardReceipt claimed() {
            return state == RewardOutboxPolicy.ReceiptState.CLAIMED
                    ? this
                    : new RewardReceipt(
                            missionId,
                            type,
                            RewardOutboxPolicy.ReceiptState.CLAIMED);
        }

        static RewardReceipt load(CompoundTag tag) {
            UUID id;
            try {
                id = UUID.fromString(tag.getString("mission_id"));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            MissionType type = MissionType.parse(tag.getString("type")).orElse(null);
            if (type == null) {
                return null;
            }
            RewardOutboxPolicy.ReceiptState state =
                    "CLAIMED".equals(tag.getString("state"))
                            ? RewardOutboxPolicy.ReceiptState.CLAIMED
                            : RewardOutboxPolicy.ReceiptState.PENDING;
            return new RewardReceipt(id, type, state);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("mission_id", missionId.toString());
            tag.putString("type", type.serializedName());
            tag.putString("state", state.name());
            return tag;
        }
    }

    /**
     * Site cleanup deferred until the site chunk loads. The mission itself is
     * already settled; only this idempotent world residue remains.
     */
    public record PendingSiteCleanup(
            UUID missionId,
            MissionType type,
            BlockPos site,
            int target) {
        static PendingSiteCleanup load(CompoundTag tag) {
            UUID id;
            try {
                id = UUID.fromString(tag.getString("mission_id"));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            MissionType type = MissionType.parse(tag.getString("type")).orElse(null);
            if (type == null || !tag.contains("site")) {
                return null;
            }
            return new PendingSiteCleanup(
                    id,
                    type,
                    BlockPos.of(tag.getLong("site")),
                    Math.max(1, tag.getInt("target")));
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("mission_id", missionId.toString());
            tag.putString("type", type.serializedName());
            tag.putLong("site", site.asLong());
            tag.putInt("target", target);
            return tag;
        }
    }

    /** Result of answering the current optional mission proposal. */
    public enum ProposalAnswer {
        ACCEPTED,
        SKIPPED,
        NOT_FOUND,
        STALE_ID,
        STALE_REVISION,
        WRONG_STAGE,
        SLOTS_FULL,
        /** Internal: the proposal matches and the change may be applied. */
        OK_FOR_APPLY
    }

    public enum OptionalSkipResult {
        SKIPPED,
        NOT_FOUND,
        AMBIGUOUS,
        STALE_REVISION,
        WRONG_STAGE
    }
}
