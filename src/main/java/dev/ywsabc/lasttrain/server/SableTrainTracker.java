package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Narrow reflected adapter for identifying and locating the starter Sable body. */
public final class SableTrainTracker {
    private static final String CONTAINER_CLASS =
            "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final String STARTER_TAG = "lasttrain_starter_train";
    private static final String CAMPAIGN_TAG = "lasttrain_campaign_id";
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();

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

    public static boolean tagStarterTrain(
            ServerLevel level,
            UUID sublevelId,
            UUID campaignId) {
        try {
            Object subLevel = subLevel(level, sublevelId);
            if (subLevel == null) {
                return false;
            }
            Method getUserData = subLevel.getClass().getMethod("getUserDataTag");
            Object rawTag = getUserData.invoke(subLevel);
            if (!(rawTag instanceof CompoundTag tag)) {
                return false;
            }
            tag.putBoolean(STARTER_TAG, true);
            tag.putString(CAMPAIGN_TAG, campaignId.toString());
            subLevel.getClass().getMethod("setUserDataTag", CompoundTag.class).invoke(subLevel, tag);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure("tag the starter Sable sublevel", exception);
            return false;
        }
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

    private static void logFailure(String action, Throwable exception) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            LastTrain.LOGGER.warn("Could not {} through the pinned Sable API", action, exception);
        }
    }
}
