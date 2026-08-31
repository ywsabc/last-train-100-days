package dev.ywsabc.lasttrain.mission;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 世界侧关键任务物品补发、去重和核销适配器。 */
public final class CriticalMissionItemDirector {
    private static final int UPDATE_ALL = 3;
    private static final double DROP_SCAN_RADIUS = 48.0D;
    private static final int MAX_DROPS_PER_SCAN = 128;
    private static final String CRATE_MISSION_TAG = "lasttrain_critical_crate_mission";

    private CriticalMissionItemDirector() {
    }

    public static BlockPos controlPos(ActiveMission mission) {
        return mission.site().offset(0, 0, 6);
    }

    public static BlockPos recoveryCratePos(ActiveMission mission) {
        return mission.site().offset(0, 0, 8);
    }

    /** 为当前关键物品阶段准备受保护控制箱和专用补发桶。 */
    public static boolean prepare(ServerLevel level, ActiveMission mission) {
        if (CriticalMissionItemRegistry.requiredBy(mission).isEmpty()) {
            return true;
        }
        level.setBlock(
                controlPos(mission),
                Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),
                UPDATE_ALL);
        level.setBlock(
                recoveryCratePos(mission).below(),
                Blocks.STONE_BRICKS.defaultBlockState(),
                UPDATE_ALL);
        return ensureRecoveryCrate(level, mission) != null;
    }

    /** 每个任务导演周期扫描已知持有位置，丢失则补发，多份则只保留一份。 */
    public static void reconcile(
            MinecraftServer server,
            CampaignSavedData data,
            ActiveMission mission) {
        Optional<CriticalMissionItemRegistry.Key> required =
                CriticalMissionItemRegistry.requiredBy(mission);
        if (required.isEmpty() || mission.site() == null || !mission.worldPrepared()) {
            return;
        }
        ServerLevel level = server.overworld();
        if (!level.hasChunkAt(mission.site())) {
            return;
        }
        Container crate = ensureRecoveryCrate(level, mission);
        if (crate == null) {
            return;
        }

        Map<String, LocatedStack> located = collect(server, level, crate, mission);
        List<CriticalItemRecoveryPolicy.ObservedCopy> observed = located.values().stream()
                .map(copy -> new CriticalItemRecoveryPolicy.ObservedCopy(
                        copy.location(),
                        copy.validMarker() ? copy.mark().generation() : 0L))
                .toList();
        CriticalItemRecoveryPolicy.Plan plan = CriticalItemRecoveryPolicy.reconcile(
                mission.criticalItemGeneration(),
                mission.criticalItemRedeemed(),
                observed);

        for (String location : plan.invalidateLocations()) {
            LocatedStack copy = located.get(location);
            if (copy != null) {
                copy.remove();
            }
        }
        plan.keepLocation().map(located::get).ifPresent(LocatedStack::keepOne);

        if (plan.issueReplacement()) {
            long generation = data.issueMissionCriticalItem();
            if (generation <= 0L) {
                return;
            }
            ItemStack replacement = createStack(mission, required.orElseThrow(), generation);
            if (replacement.isEmpty()) {
                return;
            }
            if (!insert(crate, replacement)) {
                ItemEntity drop = new ItemEntity(
                        level,
                        recoveryCratePos(mission).getX() + 0.5D,
                        recoveryCratePos(mission).getY() + 1.0D,
                        recoveryCratePos(mission).getZ() + 0.5D,
                        replacement);
                drop.setUnlimitedLifetime();
                level.addFreshEntity(drop);
            }
        }
    }

    /** 控制箱只接受当前任务、当前阶段、当前代次的注册物品。 */
    public static boolean tryRedeem(
            CampaignSavedData data,
            ActiveMission mission,
            BlockPos pos,
            ItemStack held) {
        if (mission == null
                || mission.site() == null
                || !controlPos(mission).equals(pos)
                || held.isEmpty()) {
            return false;
        }
        Mark mark = readMark(held).orElse(null);
        if (mark == null
                || !CriticalMissionItemRegistry.accepts(
                        mission,
                        mark.missionId(),
                        mark.key(),
                        mark.phase(),
                        mark.generation(),
                        mark.itemId())
                || !data.redeemMissionCriticalItem(mark.key(), mark.generation())) {
            return false;
        }
        held.shrink(1);
        return true;
    }

    /** 用于掉落实体入口立刻拒绝已完成任务或旧代次的迟到副本。 */
    public static boolean isInvalidFor(ActiveMission mission, ItemStack stack) {
        Mark mark = readMark(stack).orElse(null);
        return mark != null
                && !CriticalMissionItemRegistry.accepts(
                        mission,
                        mark.missionId(),
                        mark.key(),
                        mark.phase(),
                        mark.generation(),
                        mark.itemId());
    }

    public static Optional<Mark> readMark(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag == null
                || !tag.contains(CriticalMissionItemRegistry.MISSION_ID_TAG)
                || !tag.contains(CriticalMissionItemRegistry.KEY_TAG)
                || !tag.contains(CriticalMissionItemRegistry.PHASE_TAG)
                || !tag.contains(CriticalMissionItemRegistry.GENERATION_TAG)) {
            return Optional.empty();
        }
        try {
            UUID missionId = UUID.fromString(
                    tag.getString(CriticalMissionItemRegistry.MISSION_ID_TAG));
            CriticalMissionItemRegistry.Key key = CriticalMissionItemRegistry.Key.parse(
                            tag.getString(CriticalMissionItemRegistry.KEY_TAG))
                    .orElse(null);
            MissionType.MissionPhase phase = MissionType.MissionPhase.valueOf(
                    tag.getString(CriticalMissionItemRegistry.PHASE_TAG));
            long generation = tag.getLong(CriticalMissionItemRegistry.GENERATION_TAG);
            if (key == null || generation <= 0L) {
                return Optional.empty();
            }
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            return Optional.of(new Mark(missionId, key, phase, generation, itemId));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static ItemStack createStack(
            ActiveMission mission,
            CriticalMissionItemRegistry.Key key,
            long generation) {
        ResourceLocation id = ResourceLocation.parse(key.itemId());
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            LastTrain.LOGGER.error("关键任务物品注册项不存在：{}", key.itemId());
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        ItemStack stack = new ItemStack(item);
        stack.set(
                DataComponents.CUSTOM_NAME,
                Component.translatable("item.lasttrain.critical." + key.serializedName()));
        CompoundTag tag = new CompoundTag();
        tag.putString(CriticalMissionItemRegistry.MISSION_ID_TAG, mission.id().toString());
        tag.putString(CriticalMissionItemRegistry.KEY_TAG, key.serializedName());
        tag.putString(CriticalMissionItemRegistry.PHASE_TAG, mission.currentPhase().name());
        tag.putLong(CriticalMissionItemRegistry.GENERATION_TAG, generation);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static Map<String, LocatedStack> collect(
            MinecraftServer server,
            ServerLevel level,
            Container crate,
            ActiveMission mission) {
        Map<String, LocatedStack> result = new LinkedHashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Container inventory = player.getInventory();
            collectContainer(
                    result,
                    "player:" + player.getUUID() + ":",
                    inventory,
                    mission);
        }
        collectContainer(result, "crate:", crate, mission);

        List<ItemEntity> drops = new ArrayList<>();
        AABB bounds = AABB.ofSize(
                Vec3.atCenterOf(mission.site()),
                DROP_SCAN_RADIUS * 2.0D,
                DROP_SCAN_RADIUS * 2.0D,
                DROP_SCAN_RADIUS * 2.0D);
        level.getEntities(
                EntityType.ITEM,
                bounds,
                entity -> !entity.isRemoved(),
                drops,
                MAX_DROPS_PER_SCAN);
        for (ItemEntity entity : drops) {
            Mark mark = readMark(entity.getItem()).orElse(null);
            if (mark != null && mark.missionId().equals(mission.id())) {
                String location = "entity:" + entity.getUUID();
                result.put(location, LocatedStack.entity(location, entity, mark, mission));
            }
        }
        return result;
    }

    private static void collectContainer(
            Map<String, LocatedStack> result,
            String prefix,
            Container container,
            ActiveMission mission) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            Mark mark = readMark(stack).orElse(null);
            if (mark != null && mark.missionId().equals(mission.id())) {
                String location = prefix + slot;
                result.put(
                        location,
                        LocatedStack.container(location, container, slot, mark, mission));
            }
        }
    }

    private static Container ensureRecoveryCrate(ServerLevel level, ActiveMission mission) {
        BlockPos pos = recoveryCratePos(mission);
        if (!level.getBlockState(pos).is(Blocks.BARREL)) {
            level.setBlock(pos, Blocks.BARREL.defaultBlockState(), UPDATE_ALL);
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof BarrelBlockEntity barrel)) {
            return null;
        }
        barrel.getPersistentData().putString(CRATE_MISSION_TAG, mission.id().toString());
        barrel.setChanged();
        return barrel;
    }

    private static boolean insert(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                container.setItem(slot, stack);
                container.setChanged();
                return true;
            }
        }
        return false;
    }

    public record Mark(
            UUID missionId,
            CriticalMissionItemRegistry.Key key,
            MissionType.MissionPhase phase,
            long generation,
            String itemId) {
    }

    private record LocatedStack(
            String location,
            Mark mark,
            boolean validMarker,
            Runnable remover,
            Runnable keepOneAction) {
        static LocatedStack container(
                String location,
                Container container,
                int slot,
                Mark mark,
                ActiveMission mission) {
            return new LocatedStack(
                    location,
                    mark,
                    accepts(mission, mark),
                    () -> {
                        container.setItem(slot, ItemStack.EMPTY);
                        container.setChanged();
                    },
                    () -> {
                        ItemStack stack = container.getItem(slot);
                        if (stack.getCount() > 1) {
                            stack.setCount(1);
                            container.setChanged();
                        }
                    });
        }

        static LocatedStack entity(
                String location,
                ItemEntity entity,
                Mark mark,
                ActiveMission mission) {
            return new LocatedStack(
                    location,
                    mark,
                    accepts(mission, mark),
                    entity::discard,
                    () -> {
                        if (entity.getItem().getCount() > 1) {
                            entity.getItem().setCount(1);
                        }
                    });
        }

        private static boolean accepts(ActiveMission mission, Mark mark) {
            return CriticalMissionItemRegistry.accepts(
                    mission,
                    mark.missionId(),
                    mark.key(),
                    mark.phase(),
                    mark.generation(),
                    mark.itemId());
        }

        void remove() {
            remover.run();
        }

        void keepOne() {
            keepOneAction.run();
        }
    }
}
