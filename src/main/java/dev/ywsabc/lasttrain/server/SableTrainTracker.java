package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Narrow reflected adapter for identifying, locating and following the starter
 * Sable body.
 *
 * <p>All direct knowledge of the pinned Sable alpha API lives here. Campaign
 * and player lifecycle code only exchanges UUIDs and world-space positions,
 * so an alpha API failure cannot leak Sable implementation objects into a
 * long-lived queue or campaign save.</p>
 */
public final class SableTrainTracker {
    private static final String CONTAINER_CLASS =
            "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final String SABLE_CLASS = "dev.ryanhcode.sable.Sable";
    private static final String SERVER_SUBLEVEL_CLASS =
            "dev.ryanhcode.sable.sublevel.ServerSubLevel";
    private static final String TICKET_TYPE_CLASS =
            "dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType";
    private static final String HOLDING_SUBLEVEL_CLASS =
            "dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel";
    private static final String UNIT_CLASS = "net.minecraft.util.Unit";
    private static final String STARTER_TAG = "lasttrain_starter_train";
    private static final String CAMPAIGN_TAG = "lasttrain_campaign_id";
    private static final String GATHER_X_TAG = "lasttrain_gather_x";
    private static final String GATHER_Y_TAG = "lasttrain_gather_y";
    private static final String GATHER_Z_TAG = "lasttrain_gather_z";
    private static final double GATHERING_INTEGER_TOLERANCE = 1.0E-7D;
    private static final Set<String> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();

    private SableTrainTracker() {
    }

    public static Optional<Vec3> position(ServerLevel level, UUID sublevelId) {
        if (sublevelId == null) {
            return Optional.empty();
        }
        try {
            Object subLevel = subLevel(level, sublevelId);
            if (subLevel == null || (boolean) subLevel.getClass().getMethod("isRemoved").invoke(subLevel)) {
                return Optional.empty();
            }
            Object pose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
            Object position = pose.getClass().getMethod("position").invoke(pose);
            double x = number(position, "x");
            double y = number(position, "y");
            double z = number(position, "z");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return Optional.empty();
            }
            return Optional.of(new Vec3(x, y, z));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("locate the starter Sable sublevel", exception);
            return Optional.empty();
        }
    }

    /** Resolves the first currently intact gathering-deck slot. */
    public static Optional<Vec3> gatheringPoint(ServerLevel level, UUID sublevelId) {
        return gatheringPoints(level, sublevelId).stream().findFirst();
    }

    /**
     * Resolves every intact cell of the starter train's 3x3 gathering deck.
     *
     * <p>The saved coordinates are Sable plot-local positions. Before a slot
     * is offered to the player lifecycle, the hidden plot must still contain
     * its oak floor and two air blocks of headroom. Old vehicles without this
     * tag, or player-modified unsafe decks, deliberately fall back to the
     * starter station instead of guessing from the physics pose.</p>
     */
    public static List<Vec3> gatheringPoints(ServerLevel level, UUID sublevelId) {
        if (sublevelId == null) {
            return List.of();
        }
        try {
            Object subLevel = activeSubLevel(level, sublevelId);
            if (subLevel == null) {
                return List.of();
            }
            CompoundTag tag = userData(subLevel);
            if (tag == null
                    || !tag.contains(GATHER_X_TAG)
                    || !tag.contains(GATHER_Y_TAG)
                    || !tag.contains(GATHER_Z_TAG)) {
                return List.of();
            }

            Vec3 localCenter = new Vec3(
                    tag.getDouble(GATHER_X_TAG),
                    tag.getDouble(GATHER_Y_TAG),
                    tag.getDouble(GATHER_Z_TAG));
            if (!finite(localCenter)) {
                return List.of();
            }

            int[][] offsets = {
                {0, 0},
                {-1, 0}, {1, 0}, {0, -1}, {0, 1},
                {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
            };
            List<Vec3> result = new ArrayList<>(offsets.length);
            for (int[] offset : offsets) {
                Vec3 local = localCenter.add(offset[0], 0.0D, offset[1]);
                if (!isSafeGatheringSlot(subLevel, local)) {
                    continue;
                }
                transformPosition(subLevel, local, false).ifPresent(result::add);
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("resolve the starter train gathering point", exception);
            return List.of();
        }
    }

    /** Returns true only when Sable currently tracks the player on this exact body. */
    public static boolean isTracking(ServerPlayer player, UUID sublevelId) {
        if (sublevelId == null) {
            return false;
        }
        try {
            Class<?> sableType = Class.forName(SABLE_CLASS);
            Field helperField = sableType.getField("HELPER");
            Object helper = helperField.get(null);
            if (helper == null) {
                return false;
            }
            Object tracked = helper.getClass()
                    .getMethod("getTrackingSubLevel", Entity.class)
                    .invoke(helper, player);
            if (tracked == null
                    || (boolean) tracked.getClass().getMethod("isRemoved").invoke(tracked)) {
                return false;
            }
            Object rawId = tracked.getClass().getMethod("getUniqueId").invoke(tracked);
            return sublevelId.equals(rawId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("inspect the player's tracked Sable sublevel", exception);
            return false;
        }
    }

    /**
     * Adds Sable's persisted command-forced ticket to the campaign train. This
     * keeps an empty moving train out of holding storage while players rejoin.
     */
    public static boolean ensureForceLoaded(ServerLevel level, UUID sublevelId) {
        if (sublevelId == null) {
            return false;
        }
        try {
            Object container = container(level);
            Object subLevel = activeSubLevel(level, sublevelId);
            if (subLevel == null) {
                subLevel = restoreHoldingSubLevel(container, sublevelId);
            }
            if (subLevel == null) {
                logMessageOnce(
                        "force-load-unavailable-" + sublevelId,
                        "Starter train {} is neither active nor indexed in Sable's currently loaded holding chunks; recovery will retry",
                        sublevelId);
                return false;
            }
            Class<?> serverSubLevelType = Class.forName(SERVER_SUBLEVEL_CLASS);
            Class<?> ticketType = Class.forName(TICKET_TYPE_CLASS);
            Object commandForced = ticketType.getField("COMMAND_FORCED").get(null);
            Object unit = Class.forName(UNIT_CLASS).getField("INSTANCE").get(null);
            container.getClass()
                    .getMethod(
                            "addForceLoadTicket",
                            serverSubLevelType,
                            ticketType,
                            Object.class)
                    .invoke(container, subLevel, commandForced, unit);
            // A false API return only means this persistent ticket already exists.
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("force-load the starter Sable sublevel", exception);
            return false;
        }
    }

    public static boolean tagStarterTrain(
            ServerLevel level,
            UUID sublevelId,
            UUID campaignId) {
        return tagStarterTrain(level, sublevelId, campaignId, null);
    }

    /**
     * Tags the body for recovery and snapshots a known-safe world position in
     * train-local coordinates. The latter keeps rejoin placement correct while
     * the train translates or rotates.
     */
    public static boolean tagStarterTrain(
            ServerLevel level,
            UUID sublevelId,
            UUID campaignId,
            Vec3 globalGatheringPoint) {
        try {
            Object subLevel = subLevel(level, sublevelId);
            if (subLevel == null) {
                return false;
            }
            CompoundTag tag = userData(subLevel);
            if (tag == null) {
                // Sable 2.0.5 新装配的 ServerSubLevel 初始 user data 仍可能为空，
                // 第一个写入方必须显式创建容器。
                tag = new CompoundTag();
            }
            tag.putBoolean(STARTER_TAG, true);
            tag.putString(CAMPAIGN_TAG, campaignId.toString());
            if (globalGatheringPoint != null) {
                Optional<Vec3> local = transformPosition(subLevel, globalGatheringPoint, true);
                if (local.isPresent()) {
                    tag.putDouble(GATHER_X_TAG, local.orElseThrow().x);
                    tag.putDouble(GATHER_Y_TAG, local.orElseThrow().y);
                    tag.putDouble(GATHER_Z_TAG, local.orElseThrow().z);
                } else {
                    LastTrain.LOGGER.warn(
                            "Starter train {} was tagged, but its local gathering point could not be derived",
                            sublevelId);
                }
            }
            subLevel.getClass().getMethod("setUserDataTag", CompoundTag.class).invoke(subLevel, tag);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("tag the starter Sable sublevel", exception);
            return false;
        }
    }

    static Set<UUID> subLevelIds(ServerLevel level) throws ReflectiveOperationException {
        Object container = container(level);
        Object rawSubLevels = container.getClass().getMethod("getAllSubLevels").invoke(container);
        if (!(rawSubLevels instanceof Iterable<?> subLevels)) {
            throw new IllegalStateException("Sable getAllSubLevels() no longer returns an iterable value");
        }

        Set<UUID> ids = new HashSet<>();
        for (Object subLevel : subLevels) {
            Object rawId = subLevel.getClass().getMethod("getUniqueId").invoke(subLevel);
            if (!(rawId instanceof UUID id)) {
                throw new IllegalStateException("Sable sublevel has no UUID identity");
            }
            ids.add(id);
        }
        return ids;
    }

    public static Optional<UUID> findTaggedStarterTrain(
            ServerLevel level,
            UUID campaignId) {
        try {
            Object container = container(level);
            Object rawSubLevels = container.getClass().getMethod("getAllSubLevels").invoke(container);
            if (!(rawSubLevels instanceof Iterable<?> subLevels)) {
                return Optional.empty();
            }

            UUID found = null;
            for (Object subLevel : subLevels) {
                Object rawTag = subLevel.getClass().getMethod("getUserDataTag").invoke(subLevel);
                if (!(rawTag instanceof CompoundTag tag)
                        || !tag.getBoolean(STARTER_TAG)
                        || !campaignId.toString().equals(tag.getString(CAMPAIGN_TAG))) {
                    continue;
                }
                Object rawId = subLevel.getClass().getMethod("getUniqueId").invoke(subLevel);
                if (!(rawId instanceof UUID id)) {
                    continue;
                }
                if (found != null && !found.equals(id)) {
                    LastTrain.LOGGER.error(
                            "Multiple Sable sublevels claim to be the starter train for campaign {}",
                            campaignId);
                    return Optional.empty();
                }
                found = id;
            }
            return Optional.ofNullable(found);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("recover the tagged starter Sable sublevel", exception);
            return Optional.empty();
        }
    }

    private static Object subLevel(ServerLevel level, UUID sublevelId)
            throws ReflectiveOperationException {
        Object container = container(level);
        return container.getClass().getMethod("getSubLevel", UUID.class).invoke(container, sublevelId);
    }

    private static Object activeSubLevel(ServerLevel level, UUID sublevelId)
            throws ReflectiveOperationException {
        Object subLevel = subLevel(level, sublevelId);
        if (subLevel == null || (boolean) subLevel.getClass().getMethod("isRemoved").invoke(subLevel)) {
            return null;
        }
        return subLevel;
    }

    private static Object restoreHoldingSubLevel(Object container, UUID sublevelId)
            throws ReflectiveOperationException {
        Object holdingMap = container.getClass().getMethod("getHoldingChunkMap").invoke(container);
        if (holdingMap == null) {
            return null;
        }
        Object holding = holdingMap.getClass()
                .getMethod("getHoldingSubLevel", UUID.class)
                .invoke(holdingMap, sublevelId);
        if (holding == null) {
            return null;
        }
        Class<?> holdingType = Class.forName(HOLDING_SUBLEVEL_CLASS);
        holdingMap.getClass()
                .getMethod("loadHoldingSubLevel", holdingType)
                .invoke(holdingMap, holding);
        Object restored = container.getClass()
                .getMethod("getSubLevel", UUID.class)
                .invoke(container, sublevelId);
        if (restored != null) {
            logMessageOnce(
                    "holding-restored-" + sublevelId,
                    "Restored starter train {} from Sable holding storage before adding its persistent ticket",
                    sublevelId);
        }
        return restored;
    }

    private static Object container(ServerLevel level) throws ReflectiveOperationException {
        Class<?> containerType = Class.forName(CONTAINER_CLASS);
        Object container = containerType.getMethod("getContainer", ServerLevel.class).invoke(null, level);
        if (container == null) {
            throw new IllegalStateException("Sable has not initialized its server sublevel container");
        }
        return container;
    }

    private static double number(Object target, String accessor) throws ReflectiveOperationException {
        Object value = target.getClass().getMethod(accessor).invoke(target);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Sable pose coordinate " + accessor + " is not numeric");
        }
        return number.doubleValue();
    }

    private static CompoundTag userData(Object subLevel) throws ReflectiveOperationException {
        Method getUserData = subLevel.getClass().getMethod("getUserDataTag");
        Object rawTag = getUserData.invoke(subLevel);
        return rawTag instanceof CompoundTag tag ? tag : null;
    }

    static Optional<Vec3> transformPosition(
            Object subLevel,
            Vec3 position,
            boolean inverse) {
        try {
            Object pose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
            String methodName = inverse ? "transformPositionInverse" : "transformPosition";
            Object transformed = pose.getClass()
                    .getMethod(methodName, Vec3.class)
                    .invoke(pose, position);
            if (!(transformed instanceof Vec3 result) || !finite(result)) {
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("transform a Sable train position", exception);
            return Optional.empty();
        }
    }

    static boolean isSafeGatheringSlot(Object subLevel, Vec3 plotFeet)
            throws ReflectiveOperationException {
        // pose.transformPositionInverse returns an absolute coordinate in
        // Sable's hidden plot. EmbeddedPlotLevelAccessor instead accepts a
        // coordinate relative to the plot center and performs the hidden
        // parent-world offset itself.
        Object plot = subLevel.getClass().getMethod("getPlot").invoke(subLevel);
        Object rawCenter = plot.getClass().getMethod("getCenterBlock").invoke(plot);
        if (!(rawCenter instanceof BlockPos plotCenter)) {
            throw new IllegalStateException("Sable plot center is not a BlockPos");
        }
        Object accessor = plot.getClass()
                .getMethod("getEmbeddedLevelAccessor")
                .invoke(plot);
        Method getBlockState = accessor.getClass().getMethod("getBlockState", BlockPos.class);
        BlockPos feet = embeddedPlotPosition(plotCenter, gatheringFeetBlock(plotFeet));
        Object rawFloor = getBlockState.invoke(accessor, feet.below());
        Object rawFeet = getBlockState.invoke(accessor, feet);
        Object rawHead = getBlockState.invoke(accessor, feet.above());
        if (!(rawFloor instanceof BlockState floor)
                || !(rawFeet instanceof BlockState feetState)
                || !(rawHead instanceof BlockState headState)) {
            throw new IllegalStateException("Sable plot accessor returned a non-block state");
        }
        return safeGatheringStates(
                floor.is(Blocks.OAK_PLANKS),
                feetState.isAir(),
                headState.isAir());
    }

    static boolean safeGatheringStates(
            boolean oakFloor,
            boolean feetAir,
            boolean headAir) {
        return oakFloor && feetAir && headAir;
    }

    static BlockPos gatheringFeetBlock(Vec3 plotFeet) {
        int stableY = stableIntegralFloor(plotFeet.y);
        return BlockPos.containing(plotFeet.x, stableY, plotFeet.z);
    }

    static BlockPos embeddedPlotPosition(BlockPos plotCenter, BlockPos plotAbsolute) {
        return new BlockPos(
                plotAbsolute.getX() - plotCenter.getX(),
                plotAbsolute.getY() - plotCenter.getY(),
                plotAbsolute.getZ() - plotCenter.getZ());
    }

    private static int stableIntegralFloor(double value) {
        double nearestInteger = Math.rint(value);
        if (Math.abs(value - nearestInteger) <= GATHERING_INTEGER_TOLERANCE
                && nearestInteger >= Integer.MIN_VALUE
                && nearestInteger <= Integer.MAX_VALUE) {
            return (int) nearestInteger;
        }
        return (int) Math.floor(value);
    }

    private static boolean finite(Vec3 position) {
        return Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    private static void logFailure(String action, Throwable exception) {
        if (LOGGED_FAILURES.add(action)) {
            LastTrain.LOGGER.warn("Could not {} through the pinned Sable API", action, exception);
        }
    }

    private static void logMessageOnce(String key, String message, Object argument) {
        if (LOGGED_FAILURES.add(key)) {
            LastTrain.LOGGER.warn(message, argument);
        }
    }
}
