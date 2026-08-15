package dev.ywsabc.lasttrain.mission;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.route.RouteDirector;
import dev.ywsabc.lasttrain.route.RouteGeometry;
import dev.ywsabc.lasttrain.route.RouteTrackStates;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Materializes mission state into ordinary server-authoritative world
 * interactions and observes completion from the world.
 */
public final class MissionWorldDirector {
    private static final int UPDATE_ALL = 3;
    private static final int TICK_INTERVAL = 10;
    private static final String MISSION_ENTITY_TAG_PREFIX = "lasttrain_mission_";
    private static final String SURVIVOR_TAG_SUFFIX = "_survivor";
    private static final String SUPPLY_MISSION_ID_KEY = "lasttrain_supply_mission_id";
    private static final String SUPPLY_INDEX_KEY = "lasttrain_supply_index";

    private MissionWorldDirector() {
    }

    /**
     * X offsets, relative to the mission site, for every materialized
     * objective of a mission. The default targets preserve the original
     * prototype layouts; larger frozen targets extend them symmetrically so
     * one save keeps the same interpretation after a mod update.
     */
    static int[] objectiveXOffsets(MissionType type, int target) {
        int count = Math.max(1, target);
        int[] offsets = new int[count];
        return switch (type) {
            case RAIL_BREAK, SUPPLY_RECOVERY -> {
                int first = -(count / 2);
                for (int index = 0; index < count; index++) {
                    offsets[index] = first + index;
                }
                yield offsets;
            }
            case STATION_POWER, STATION_GATE -> {
                int first = -(count - 1);
                for (int index = 0; index < count; index++) {
                    offsets[index] = first + 2 * index;
                }
                yield offsets;
            }
            case ZOMBIE_BLOCKADE, RESCUE_SURVIVOR, SALVAGE_CAR -> new int[0];
        };
    }

    private static boolean containsObjectiveXOffset(int[] offsets, int dx) {
        for (int offset : offsets) {
            if (offset == dx) {
                return true;
            }
        }
        return false;
    }

    private static int maxAbs(int[] values) {
        int max = 0;
        for (int value : values) {
            max = Math.max(max, Math.abs(value));
        }
        return max;
    }

    public static void tick(MinecraftServer server, CampaignSavedData data, int serverTick) {
        if (serverTick % TICK_INTERVAL != 0) {
            return;
        }
        // Optional missions run beside the mainline: timeouts, outbox
        // dispatch and site work proceed even while no mainline blocker or
        // no prepared mainline site exists.
        OptionalMissionDirector.tick(server, data);

        ActiveMission mission = data.activeMission();
        if (mission == null) {
            return;
        }
        if (MissionFallbackPolicy.shouldFallback(
                mission,
                data.day(),
                data.isFinaleMission(mission))) {
            if (canClearFallbackWorld(server.overworld(), mission)
                    && data.failMission(MissionFallbackPolicy.THREAT_PENALTY)) {
                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable(
                                "message.lasttrain.mission_failed",
                                Component.translatable(
                                        "mission.lasttrain." + mission.type().serializedName()),
                                data.threat()),
                        false);
            }
            // The cleanup may still need its chunks loaded. Do not prepare,
            // repair, or observe an expired roadblock while that is pending.
            return;
        }
        // Reconciliation is deliberately periodic: it repairs entity/save
        // skew quickly without scanning every loaded entity twenty times per
        // second for the whole duration of a blockade.
        if (canReconcileZombieBlockade(server, data, mission)) {
            reconcileZombieBlockade(server.overworld(), mission);
        }

        if (mission.site() == null) {
            data.assignMissionSite(RouteDirector.missionAnchor(data, mission.routeSegment()));
            mission = data.activeMission();
        }
        BlockPos site = mission.site();
        int requiredSegment = RouteGeometry.missionSegment(mission.routeSegment());
        if (site == null
                || data.generatedRouteSegment() < requiredSegment
                || !server.overworld().hasChunkAt(site)) {
            return;
        }

        if (mission.type() == MissionType.ZOMBIE_BLOCKADE
                && !mission.worldPrepared()
                && !hasNearbyPlayer(server.overworld(), site)) {
            return;
        }

        if (!mission.worldPrepared()) {
            if (!prepare(server.overworld(), mission)) {
                return;
            }
            data.markMissionWorldPrepared();
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable(
                            "message.lasttrain.mission_site",
                            site.getX(),
                            site.getY(),
                            site.getZ()),
                    false);
            mission = data.activeMission();
        }

        if (!repairPreparedMission(server.overworld(), mission)) {
            return;
        }
        if (mission.stage() == MissionStage.READY_TO_TURN_IN) {
            // SavedData and chunk saves are not one atomic transaction. Repair
            // the passable route idempotently after a restart even when the
            // READY state reached disk before the barrier removal did.
            resolveRouteBarrier(server.overworld(), mission);
            return;
        }
        if (mission.stage() != MissionStage.ACTIVE) {
            return;
        }
        int observed = observeProgress(server.overworld(), mission);
        MissionStage before = mission.stage();
        if (data.setMissionObservedProgress(observed)
                && before != MissionStage.READY_TO_TURN_IN
                && mission.stage() == MissionStage.READY_TO_TURN_IN) {
            resolveRouteBarrier(server.overworld(), mission);
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable(
                            "message.lasttrain.mission_ready",
                            Component.translatable(
                                    "mission.lasttrain." + mission.type().serializedName())),
                    false);
        }
    }

    private static boolean prepare(ServerLevel level, ActiveMission mission) {
        try {
            buildMissionApron(level, mission);
            return switch (mission.type()) {
                case RAIL_BREAK -> prepareRailBreak(level, mission);
                case STATION_POWER -> prepareStationPower(level, mission);
                case STATION_GATE -> prepareStationGate(level, mission);
                case SUPPLY_RECOVERY -> prepareSupplyRecovery(level, mission);
                case ZOMBIE_BLOCKADE -> prepareZombieBlockade(level, mission);
                case RESCUE_SURVIVOR, SALVAGE_CAR -> true;
            };
        } catch (RuntimeException exception) {
            LastTrain.LOGGER.error(
                    "Could not prepare mission {} at {}",
                    mission.id(),
                    mission.site(),
                    exception);
            return false;
        }
    }

    private static int observeProgress(ServerLevel level, ActiveMission mission) {
        return switch (mission.type()) {
            case RAIL_BREAK -> observeRailRepair(level, mission);
            case STATION_POWER -> observePoweredLevers(level, mission);
            case STATION_GATE -> observeOpenDoors(level, mission);
            case SUPPLY_RECOVERY -> observeRecoveredBarrels(level, mission);
            case ZOMBIE_BLOCKADE, RESCUE_SURVIVOR, SALVAGE_CAR -> mission.progress();
        };
    }

    private static void buildMissionApron(ServerLevel level, ActiveMission mission) {
        BlockPos floor = mission.site().below();
        int[] offsets = objectiveXOffsets(mission.type(), mission.target());
        int halfWidth = Math.max(5, maxAbs(offsets) + 2);
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int z = -7; z <= 7; z++) {
                if (Math.abs(z) <= 1) {
                    continue;
                }
                level.setBlock(
                        floor.offset(x, 0, z),
                        Blocks.STONE_BRICKS.defaultBlockState(),
                        UPDATE_ALL);
            }
        }
    }

    private static boolean prepareRailBreak(ServerLevel level, ActiveMission mission) {
        BlockPos site = mission.site();
        for (int offset : objectiveXOffsets(mission.type(), mission.target())) {
            BlockPos track = site.offset(offset, 0, 0);
            level.setBlock(track, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
            level.setBlock(
                    track.below(),
                    Blocks.RED_CONCRETE.defaultBlockState(),
                    UPDATE_ALL);
        }
        return true;
    }

    private static boolean prepareStationPower(ServerLevel level, ActiveMission mission) {
        for (int offset : objectiveXOffsets(mission.type(), mission.target())) {
            BlockPos base = mission.site().offset(offset, -1, 4);
            level.setBlock(base, Blocks.IRON_BLOCK.defaultBlockState(), UPDATE_ALL);
            level.setBlock(
                    base.above(),
                    state(
                            Blocks.LEVER,
                            "face", "floor",
                            "facing", "north",
                            "powered", "false"),
                    UPDATE_ALL);
            level.setBlock(
                    base.offset(0, 0, 1),
                    Blocks.REDSTONE_LAMP.defaultBlockState(),
                    UPDATE_ALL);
        }
        prepareRouteBarrier(level, mission.site());
        return true;
    }

    private static boolean prepareStationGate(ServerLevel level, ActiveMission mission) {
        int[] offsets = objectiveXOffsets(mission.type(), mission.target());
        for (int index = 0; index < offsets.length; index++) {
            BlockPos lower = mission.site().offset(offsets[index], 0, 4);
            placeGateDoor(level, lower, index);
            level.setBlock(
                    lower.offset(0, 0, -1),
                    state(
                            Blocks.LEVER,
                            "face", "floor",
                            "facing", "north",
                            "powered", "false"),
                    UPDATE_ALL);
        }
        prepareRouteBarrier(level, mission.site());
        return true;
    }

    private static void placeGateDoor(ServerLevel level, BlockPos lower, int index) {
        level.setBlock(
                lower,
                state(
                        Blocks.IRON_DOOR,
                        "facing", "east",
                        "half", "lower",
                        "hinge", index == 0 ? "left" : "right",
                        "open", "false",
                        "powered", "false"),
                UPDATE_ALL);
        level.setBlock(
                lower.above(),
                state(
                        Blocks.IRON_DOOR,
                        "facing", "east",
                        "half", "upper",
                        "hinge", index == 0 ? "left" : "right",
                        "open", "false",
                        "powered", "false"),
                UPDATE_ALL);
    }

    private static boolean prepareSupplyRecovery(ServerLevel level, ActiveMission mission) {
        net.minecraft.world.item.Item[] supplies = {
            Items.IRON_INGOT,
            Items.BREAD,
            Items.REDSTONE,
            Items.CHARCOAL,
            Items.ARROW
        };
        int[] counts = {4, 8, 8, 8, 16};
        int[] offsets = objectiveXOffsets(mission.type(), mission.target());
        for (int index = 0; index < offsets.length; index++) {
            BlockPos barrelPos = mission.site().offset(offsets[index], 0, 4);
            level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), UPDATE_ALL);
            BlockEntity blockEntity = level.getBlockEntity(barrelPos);
            if (!(blockEntity instanceof Container barrel)) {
                return false;
            }
            barrel.clearContent();
            barrel.setItem(
                    0,
                    new ItemStack(
                            supplies[index % supplies.length],
                            counts[index % counts.length]));
            blockEntity.getPersistentData().putString(
                    SUPPLY_MISSION_ID_KEY,
                    mission.id().toString());
            blockEntity.getPersistentData().putInt(SUPPLY_INDEX_KEY, index);
            blockEntity.setChanged();
        }
        return true;
    }

    private static boolean prepareZombieBlockade(ServerLevel level, ActiveMission mission) {
        return reconcileZombieBlockade(level, mission);
    }

    /**
     * Repairs only the irreplaceable mission controls. Player-authored progress
     * (placed track, powered levers, opened doors and recovered contents) is
     * preserved, while an explosion or accidental break cannot permanently
     * strand the campaign checkpoint.
     */
    private static boolean repairPreparedMission(ServerLevel level, ActiveMission mission) {
        if (!mission.worldPrepared() || mission.stage() != MissionStage.ACTIVE) {
            return true;
        }
        try {
            return switch (mission.type()) {
                case RAIL_BREAK, ZOMBIE_BLOCKADE, RESCUE_SURVIVOR, SALVAGE_CAR -> true;
                case STATION_POWER -> {
                    repairStationPower(level, mission);
                    ensureRouteBarrier(level, mission.site());
                    yield true;
                }
                case STATION_GATE -> {
                    repairStationGate(level, mission);
                    ensureRouteBarrier(level, mission.site());
                    yield true;
                }
                case SUPPLY_RECOVERY -> repairSupplyBarrels(level, mission);
            };
        } catch (RuntimeException exception) {
            LastTrain.LOGGER.error(
                    "Could not repair mission controls for {} at {}",
                    mission.id(),
                    mission.site(),
                    exception);
            return false;
        }
    }

    private static void repairStationPower(ServerLevel level, ActiveMission mission) {
        for (int offset : objectiveXOffsets(mission.type(), mission.target())) {
            BlockPos base = mission.site().offset(offset, -1, 4);
            if (!level.getBlockState(base).is(Blocks.IRON_BLOCK)) {
                level.setBlock(base, Blocks.IRON_BLOCK.defaultBlockState(), UPDATE_ALL);
            }
            BlockPos lever = base.above();
            if (!level.getBlockState(lever).is(Blocks.LEVER)) {
                level.setBlock(
                        lever,
                        state(
                                Blocks.LEVER,
                                "face", "floor",
                                "facing", "north",
                                "powered", "false"),
                        UPDATE_ALL);
            }
            BlockPos lamp = base.offset(0, 0, 1);
            if (!level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP)) {
                level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), UPDATE_ALL);
            }
        }
    }

    private static void repairStationGate(ServerLevel level, ActiveMission mission) {
        int[] offsets = objectiveXOffsets(mission.type(), mission.target());
        for (int index = 0; index < offsets.length; index++) {
            BlockPos lower = mission.site().offset(offsets[index], 0, 4);
            if (!level.getBlockState(lower).is(Blocks.IRON_DOOR)
                    || !level.getBlockState(lower.above()).is(Blocks.IRON_DOOR)) {
                placeGateDoor(level, lower, index);
            }
            BlockPos lever = lower.offset(0, 0, -1);
            if (!level.getBlockState(lever).is(Blocks.LEVER)) {
                level.setBlock(
                        lever,
                        state(
                                Blocks.LEVER,
                                "face", "floor",
                                "facing", "north",
                                "powered", "false"),
                        UPDATE_ALL);
            }
        }
    }

    private static boolean repairSupplyBarrels(ServerLevel level, ActiveMission mission) {
        int[] offsets = objectiveXOffsets(mission.type(), mission.target());
        for (int index = 0; index < offsets.length; index++) {
            BlockPos barrelPos = mission.site().offset(offsets[index], 0, 4);
            if (!level.getBlockState(barrelPos).is(Blocks.BARREL)) {
                level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), UPDATE_ALL);
            }
            BlockEntity blockEntity = level.getBlockEntity(barrelPos);
            if (!(blockEntity instanceof Container)) {
                level.setBlock(barrelPos, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
                level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), UPDATE_ALL);
                blockEntity = level.getBlockEntity(barrelPos);
            }
            if (!(blockEntity instanceof Container)) {
                return false;
            }
            blockEntity.getPersistentData().putString(
                    SUPPLY_MISSION_ID_KEY,
                    mission.id().toString());
            blockEntity.getPersistentData().putInt(SUPPLY_INDEX_KEY, index);
            blockEntity.setChanged();
        }
        return true;
    }

    private static boolean canReconcileZombieBlockade(
            MinecraftServer server,
            CampaignSavedData data,
            ActiveMission mission) {
        if (mission == null
                || mission.type() != MissionType.ZOMBIE_BLOCKADE
                || mission.stage() != MissionStage.ACTIVE
                || !mission.worldPrepared()
                || mission.site() == null) {
            return false;
        }
        return data.generatedRouteSegment()
                        >= RouteGeometry.missionSegment(mission.routeSegment())
                && server.overworld().hasChunkAt(mission.site());
    }

    private static boolean reconcileZombieBlockade(ServerLevel level, ActiveMission mission) {
        String tag = missionEntityTag(mission);
        List<Zombie> living = new ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof Zombie zombie
                    && zombie.isAlive()
                    && !zombie.isRemoved()
                    && zombie.getTags().contains(tag)) {
                living.add(zombie);
            }
        }

        ZombieBlockadePolicy.Reconciliation reconciliation =
                ZombieBlockadePolicy.reconcile(
                        mission.target(),
                        mission.progress(),
                        living.size());
        for (int index = 0; index < reconciliation.toDiscard(); index++) {
            living.get(living.size() - 1 - index).discard();
        }

        int retained = living.size() - reconciliation.toDiscard();
        for (int index = 0; index < reconciliation.toSpawn(); index++) {
            Zombie zombie = EntityType.ZOMBIE.create(level);
            if (zombie == null) {
                return false;
            }
            int spawnOrdinal = retained + index;
            int side = (spawnOrdinal & 1) == 0 ? -1 : 1;
            int xOffset = (spawnOrdinal % 6) - 3;
            BlockPos spawn = mission.site().offset(xOffset, 0, side * 5);
            for (int y = 0; y <= 2; y++) {
                BlockPos clearance = spawn.above(y);
                if (!level.getBlockState(clearance).isAir()) {
                    level.setBlock(clearance, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
                }
            }
            zombie.moveTo(
                    spawn.getX() + 0.5D,
                    spawn.getY(),
                    spawn.getZ() + 0.5D,
                    side < 0 ? 0.0F : 180.0F,
                    0.0F);
            zombie.setPersistenceRequired();
            zombie.addTag(tag);
            if (!level.addFreshEntity(zombie)) {
                return false;
            }
        }
        return true;
    }

    private static int observeRailRepair(ServerLevel level, ActiveMission mission) {
        Block track = registeredBlock("create:track");
        int repaired = 0;
        for (int offset : objectiveXOffsets(mission.type(), mission.target())) {
            BlockState state = level.getBlockState(mission.site().offset(offset, 0, 0));
            if (state.is(track) && propertyIs(state, "shape", "xo")) {
                repaired++;
            }
        }
        return repaired;
    }

    private static int observePoweredLevers(ServerLevel level, ActiveMission mission) {
        int powered = 0;
        for (int offset : objectiveXOffsets(mission.type(), mission.target())) {
            BlockState state = level.getBlockState(mission.site().offset(offset, 0, 4));
            if (state.is(Blocks.LEVER) && propertyIs(state, "powered", "true")) {
                powered++;
            }
        }
        return powered;
    }

    private static int observeOpenDoors(ServerLevel level, ActiveMission mission) {
        int opened = 0;
        for (int offset : objectiveXOffsets(mission.type(), mission.target())) {
            BlockState state = level.getBlockState(mission.site().offset(offset, 0, 4));
            if (state.is(Blocks.IRON_DOOR) && propertyIs(state, "open", "true")) {
                opened++;
            }
        }
        return opened;
    }

    private static int observeRecoveredBarrels(ServerLevel level, ActiveMission mission) {
        int recovered = 0;
        int[] offsets = objectiveXOffsets(mission.type(), mission.target());
        for (int index = 0; index < offsets.length; index++) {
            BlockPos barrelPos = mission.site().offset(offsets[index], 0, 4);
            BlockEntity blockEntity = level.getBlockEntity(barrelPos);
            if (blockEntity instanceof Container barrel
                    && level.getBlockState(barrelPos).is(Blocks.BARREL)
                    && mission.id().toString().equals(
                            blockEntity.getPersistentData().getString(SUPPLY_MISSION_ID_KEY))
                    && blockEntity.getPersistentData().getInt(SUPPLY_INDEX_KEY) == index
                    && barrel.isEmpty()) {
                recovered++;
            }
        }
        return recovered;
    }

    private static boolean canClearFallbackWorld(ServerLevel level, ActiveMission mission) {
        if (!mission.worldPrepared() || mission.site() == null) {
            return true;
        }
        return clearMissionWorld(level, mission);
    }

    public static boolean clearMissionWorld(ServerLevel level, ActiveMission mission) {
        if (mission == null || mission.site() == null) {
            return true;
        }
        if (!level.hasChunkAt(mission.site())) {
            return false;
        }
        switch (mission.type()) {
            case RAIL_BREAK -> {
                Block track = registeredBlock("create:track");
                if (track == Blocks.AIR) {
                    return false;
                }
                for (int offset : objectiveXOffsets(mission.type(), mission.target())) {
                    level.setBlock(
                            mission.site().offset(offset, 0, 0),
                            RouteTrackStates.eastbound(track),
                            UPDATE_ALL);
                    level.setBlock(
                            mission.site().offset(offset, -1, 0),
                            Blocks.POLISHED_ANDESITE.defaultBlockState(),
                            UPDATE_ALL);
                }
            }
            case STATION_POWER, STATION_GATE -> resolveRouteBarrier(level, mission);
            case ZOMBIE_BLOCKADE -> {
                String tag = missionEntityTag(mission);
                for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                    if (entity instanceof Zombie zombie && zombie.getTags().contains(tag)) {
                        zombie.discard();
                    }
                }
            }
            case SUPPLY_RECOVERY -> {
                // Recovered supply barrels remain as ordinary station loot.
            }
            case RESCUE_SURVIVOR, SALVAGE_CAR -> {
                // Optional sites are cleaned up by OptionalMissionDirector.
            }
        }
        return true;
    }

    private static void prepareRouteBarrier(ServerLevel level, BlockPos site) {
        for (int y = 0; y <= 2; y++) {
            for (int z = -2; z <= 2; z++) {
                level.setBlock(
                        site.offset(0, y, z),
                        Blocks.IRON_BARS.defaultBlockState(),
                        UPDATE_ALL);
            }
        }
        level.setBlock(
                site.below(),
                Blocks.RED_CONCRETE.defaultBlockState(),
                UPDATE_ALL);
    }

    private static void ensureRouteBarrier(ServerLevel level, BlockPos site) {
        for (int y = 0; y <= 2; y++) {
            for (int z = -2; z <= 2; z++) {
                BlockPos barrier = site.offset(0, y, z);
                if (!level.getBlockState(barrier).is(Blocks.IRON_BARS)) {
                    level.setBlock(
                            barrier,
                            Blocks.IRON_BARS.defaultBlockState(),
                            UPDATE_ALL);
                }
            }
        }
        if (!level.getBlockState(site.below()).is(Blocks.RED_CONCRETE)) {
            level.setBlock(
                    site.below(),
                    Blocks.RED_CONCRETE.defaultBlockState(),
                    UPDATE_ALL);
        }
    }

    private static void resolveRouteBarrier(ServerLevel level, ActiveMission mission) {
        BlockPos site = mission.site();
        if (site == null) {
            return;
        }
        for (int y = 0; y <= 2; y++) {
            for (int z = -2; z <= 2; z++) {
                BlockPos pos = site.offset(0, y, z);
                if (level.getBlockState(pos).is(Blocks.IRON_BARS)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
                }
            }
        }
        Block track = registeredBlock("create:track");
        if (track != Blocks.AIR) {
            level.setBlock(site, RouteTrackStates.eastbound(track), UPDATE_ALL);
            level.setBlock(
                    site.below(),
                    Blocks.POLISHED_ANDESITE.defaultBlockState(),
                    UPDATE_ALL);
        }
    }

    private static boolean hasNearbyPlayer(ServerLevel level, BlockPos site) {
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double dx = player.getX() - (site.getX() + 0.5D);
            double dy = player.getY() - (site.getY() + 0.5D);
            double dz = player.getZ() - (site.getZ() + 0.5D);
            if (ZombieBlockadePolicy.isWithinActivationDistance(
                    dx * dx + dy * dy + dz * dz)) {
                return true;
            }
        }
        return false;
    }

    static String missionEntityTag(ActiveMission mission) {
        return MISSION_ENTITY_TAG_PREFIX + mission.id();
    }

    static String survivorEntityTag(ActiveMission mission) {
        return survivorEntityTag(mission.id());
    }

    static String survivorEntityTag(java.util.UUID missionId) {
        return MISSION_ENTITY_TAG_PREFIX + missionId + SURVIVOR_TAG_SUFFIX;
    }

    static boolean isMissionEntityTag(String tag) {
        return tag.startsWith(MISSION_ENTITY_TAG_PREFIX);
    }

    static boolean isSurvivorEntityTag(String tag) {
        return tag.startsWith(MISSION_ENTITY_TAG_PREFIX) && tag.endsWith(SURVIVOR_TAG_SUFFIX);
    }

    /**
     * Identifies blocks reserved by an active, materialized mission. These
     * blocks are repaired by the director, so allowing them to drop would
     * create an infinite resource loop. Gate supports are included because
     * removing one also breaks its attached control or door.
     */
    static boolean isProtectedMissionBlock(ActiveMission mission, BlockPos pos) {
        if (!hasProtectedMissionBlocks(mission)) {
            return false;
        }

        BlockPos site = mission.site();
        int dx = pos.getX() - site.getX();
        int dy = pos.getY() - site.getY();
        int dz = pos.getZ() - site.getZ();
        int[] offsets = objectiveXOffsets(mission.type(), mission.target());
        return switch (mission.type()) {
            case STATION_POWER -> isRouteBarrierOffset(dx, dy, dz)
                    || (containsObjectiveXOffset(offsets, dx)
                            && ((dy == -1 && (dz == 4 || dz == 5))
                                    || (dy == 0 && dz == 4)));
            case STATION_GATE -> isRouteBarrierOffset(dx, dy, dz)
                    || (containsObjectiveXOffset(offsets, dx)
                            && ((dz == 4 && dy >= -1 && dy <= 1)
                                    || (dz == 3 && dy >= -1 && dy <= 0)));
            case SUPPLY_RECOVERY -> dy == 0
                    && dz == 4
                    && containsObjectiveXOffset(offsets, dx);
            case SALVAGE_CAR -> OptionalMissionDirector.isProtectedSalvageBlock(mission, pos);
            case RAIL_BREAK, ZOMBIE_BLOCKADE, RESCUE_SURVIVOR -> false;
        };
    }

    static boolean hasProtectedMissionBlocks(ActiveMission mission) {
        return mission != null
                && mission.site() != null
                && mission.worldPrepared()
                && mission.stage() == MissionStage.ACTIVE
                && mission.type() != MissionType.RAIL_BREAK
                && mission.type() != MissionType.ZOMBIE_BLOCKADE
                && mission.type() != MissionType.RESCUE_SURVIVOR;
    }

    static boolean containsProtectedMissionBlock(
            ActiveMission mission,
            Iterable<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (isProtectedMissionBlock(mission, pos)) {
                return true;
            }
        }
        return false;
    }

    static boolean movesIntoProtectedMissionBlock(
            ActiveMission mission,
            Iterable<BlockPos> sources,
            Direction moveDirection) {
        for (BlockPos source : sources) {
            if (isProtectedMissionBlock(mission, source.relative(moveDirection))) {
                return true;
            }
        }
        return false;
    }

    static boolean isRegeneratedMissionDrop(
            ActiveMission mission,
            BlockPos pos,
            ItemStack stack) {
        return regeneratedMissionDropAt(mission, pos).matches(stack);
    }

    static RegeneratedMissionDrop regeneratedMissionDropAt(
            ActiveMission mission,
            BlockPos pos) {
        if (!hasProtectedMissionBlocks(mission)) {
            return RegeneratedMissionDrop.NONE;
        }

        BlockPos site = mission.site();
        int dx = pos.getX() - site.getX();
        int dy = pos.getY() - site.getY();
        int dz = pos.getZ() - site.getZ();
        int[] offsets = objectiveXOffsets(mission.type(), mission.target());
        if ((mission.type() == MissionType.STATION_POWER
                        || mission.type() == MissionType.STATION_GATE)
                && isRouteBarrierOffset(dx, dy, dz)) {
            return dy == -1
                    ? RegeneratedMissionDrop.RED_CONCRETE
                    : RegeneratedMissionDrop.IRON_BARS;
        }
        return switch (mission.type()) {
            case STATION_POWER -> {
                if (containsObjectiveXOffset(offsets, dx) && dz == 4) {
                    yield dy == -1
                            ? RegeneratedMissionDrop.IRON_BLOCK
                            : dy == 0
                                    ? RegeneratedMissionDrop.LEVER
                                    : RegeneratedMissionDrop.NONE;
                }
                yield containsObjectiveXOffset(offsets, dx) && dy == -1 && dz == 5
                        ? RegeneratedMissionDrop.REDSTONE_LAMP
                        : RegeneratedMissionDrop.NONE;
            }
            case STATION_GATE -> {
                if (containsObjectiveXOffset(offsets, dx) && dz == 4 && dy >= 0 && dy <= 1) {
                    yield RegeneratedMissionDrop.IRON_DOOR;
                }
                yield containsObjectiveXOffset(offsets, dx) && dy == 0 && dz == 3
                        ? RegeneratedMissionDrop.LEVER
                        : RegeneratedMissionDrop.NONE;
            }
            case SUPPLY_RECOVERY -> dy == 0
                    && dz == 4
                    && containsObjectiveXOffset(offsets, dx)
                    ? RegeneratedMissionDrop.BARREL
                    : RegeneratedMissionDrop.NONE;
            case RAIL_BREAK, ZOMBIE_BLOCKADE, RESCUE_SURVIVOR, SALVAGE_CAR ->
                    RegeneratedMissionDrop.NONE;
        };
    }

    enum RegeneratedMissionDrop {
        NONE,
        IRON_BARS,
        RED_CONCRETE,
        IRON_BLOCK,
        LEVER,
        REDSTONE_LAMP,
        IRON_DOOR,
        BARREL;

        boolean matches(ItemStack stack) {
            return switch (this) {
                case NONE -> false;
                case IRON_BARS -> stack.is(Blocks.IRON_BARS.asItem());
                case RED_CONCRETE -> stack.is(Blocks.RED_CONCRETE.asItem());
                case IRON_BLOCK -> stack.is(Blocks.IRON_BLOCK.asItem());
                case LEVER -> stack.is(Blocks.LEVER.asItem());
                case REDSTONE_LAMP -> stack.is(Blocks.REDSTONE_LAMP.asItem());
                case IRON_DOOR -> stack.is(Blocks.IRON_DOOR.asItem());
                case BARREL -> stack.is(Blocks.BARREL.asItem());
            };
        }
    }

    private static boolean isRouteBarrierOffset(int dx, int dy, int dz) {
        return dx == 0
                && ((dy >= 0 && dy <= 2 && Math.abs(dz) <= 2)
                        || (dy == -1 && dz == 0));
    }

    private static Block registeredBlock(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        return BuiltInRegistries.BLOCK.containsKey(key)
                ? BuiltInRegistries.BLOCK.get(key)
                : Blocks.AIR;
    }

    private static BlockState state(Block block, String... propertyPairs) {
        BlockState state = block.defaultBlockState();
        for (int index = 0; index < propertyPairs.length; index += 2) {
            String name = propertyPairs[index];
            String value = propertyPairs[index + 1];
            Property<?> property = state.getProperties().stream()
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            block + " has no block-state property " + name));
            state = applyProperty(state, property, value);
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyProperty(BlockState state, Property property, String value) {
        Optional<? extends Comparable> parsed = property.getValue(value);
        return parsed.map(candidate -> state.setValue(property, candidate))
                .orElseThrow(() -> new IllegalArgumentException(
                        property.getName() + " rejects value " + value));
    }

    private static boolean propertyIs(BlockState state, String name, String expected) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return serializedPropertyValue(state, property).equals(expected);
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String serializedPropertyValue(BlockState state, Property property) {
        Comparable value = state.getValue(property);
        return property.getName(value);
    }
}
