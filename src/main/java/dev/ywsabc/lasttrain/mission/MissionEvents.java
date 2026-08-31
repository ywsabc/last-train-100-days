package dev.ywsabc.lasttrain.mission;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.server.CampaignTickGuard;
import dev.ywsabc.lasttrain.text.TranslationKeys;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/** Event-driven mission accounting for entities that may move across chunks. */
public final class MissionEvents {
    private MissionEvents() {
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }

        MinecraftServer server = level.getServer();
        CampaignSavedData data = CampaignSavedData.get(server);
        ActiveMission mission = data.activeMission();
        // 无论死因是什么都先释放 UUID 占用；只有玩家击杀路障僵尸才推进任务。
        data.unregisterMissionEntity(event.getEntity().getUUID());
        if (!(event.getEntity() instanceof Zombie zombie)
                || !(event.getSource().getEntity() instanceof ServerPlayer)
                || mission == null
                || !MissionWorldDirector.isDefenseMission(mission)
                || mission.stage() != MissionStage.ACTIVE
                || !zombie.getTags().contains(MissionWorldDirector.missionEntityTag(mission))) {
            return;
        }

        if (data.addMissionProgress(1)
                && data.activeMission() != null
                && data.activeMission().stage() == MissionStage.READY_TO_TURN_IN) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable(
                            "message.lasttrain.mission_ready",
                            Component.translatable(TranslationKeys.mission(mission.type()))),
                    false);
        }
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        CampaignSavedData data = CampaignSavedData.get(level.getServer());
        if (!CampaignTickGuard.allowsWorldWrite(data, "mission.event.entity_join")) {
            return;
        }
        ActiveMission mission = data.activeMission();
        if (event.getEntity() instanceof ItemEntity criticalDrop
                && CriticalMissionItemDirector.isInvalidFor(mission, criticalDrop.getItem())) {
            // 旧任务、旧阶段和旧补发代次的迟到副本在进入世界时直接失效。
            event.setCanceled(true);
            return;
        }
        if (event.getEntity() instanceof ItemEntity droppedItem
                && level == level.getServer().overworld()
                && MissionWorldDirector.isRegeneratedMissionDrop(
                        mission,
                        droppedItem.blockPosition(),
                        droppedItem.getItem())) {
            // Fluids and some automation paths bypass BreakEvent. Swallow only
            // the exact block item regenerated at its reserved mission
            // coordinate so periodic repair cannot become a resource printer.
            event.setCanceled(true);
            return;
        }

        if (event.getEntity() instanceof Zombie zombie) {
            if (MissionWorldDirector.isDefenseMission(mission)) {
                String activeTag = MissionWorldDirector.missionEntityTag(mission);
                if (zombie.getTags().contains(activeTag)) {
                    if (!data.registerMissionEntity(mission.id(), zombie.getUUID())) {
                        zombie.discard();
                    }
                    return;
                }
            }
            for (String tag : zombie.getTags()) {
                if (MissionWorldDirector.isMissionEntityTag(tag)) {
                    data.unregisterMissionEntity(zombie.getUUID());
                    zombie.discard();
                    return;
                }
            }
            return;
        }

        if (event.getEntity() instanceof Villager survivor) {
            for (String tag : survivor.getTags()) {
                if (!MissionWorldDirector.isSurvivorEntityTag(tag)) {
                    continue;
                }
                ActiveMission rescue = activeRescueMission(data, tag);
                if (rescue != null) {
                    if (!data.registerMissionEntity(rescue.id(), survivor.getUUID())) {
                        survivor.discard();
                    }
                } else {
                    data.unregisterMissionEntity(survivor.getUUID());
                    survivor.discard();
                }
                return;
            }
        }
    }

    /**
     * 捕获非死亡的真正销毁路径（命令 discard、其他模组回收等）。区块卸载与换维度
     * 不移除 UUID，因为这些实体仍然存在并必须继续占用全局硬上限。
     */
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || event.getEntity().getRemovalReason() == null
                || !event.getEntity().getRemovalReason().shouldDestroy()) {
            return;
        }
        CampaignSavedData.get(level.getServer())
                .unregisterMissionEntity(event.getEntity().getUUID());
    }

    private static ActiveMission activeRescueMission(
            CampaignSavedData data,
            String survivorTag) {
        for (ActiveMission mission : data.optionalMissions()) {
            if (mission.type() == MissionType.RESCUE_SURVIVOR
                    && mission.stage() == MissionStage.ACTIVE
                    && MissionWorldDirector.survivorEntityTag(mission).equals(survivorTag)) {
                return mission;
            }
        }
        return null;
    }

    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld()) {
            return;
        }

        CampaignSavedData data = CampaignSavedData.get(level.getServer());
        if (MissionWorldDirector.isProtectedMissionBlock(data.activeMission(), event.getPos())
                || data.optionalMissions().stream()
                        .anyMatch(mission -> MissionWorldDirector.isProtectedMissionBlock(
                                mission,
                                event.getPos()))) {
            event.setCanceled(true);
        }
    }

    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld()) {
            return;
        }

        CampaignSavedData data = CampaignSavedData.get(level.getServer());
        boolean hasActivePlayers = level.getServer().getPlayerList().getPlayers().stream()
                .anyMatch(player -> !player.isSpectator());
        data.registerExplosion(hasActivePlayers);
        event.getAffectedBlocks().removeIf(pos ->
                MissionWorldDirector.isProtectedMissionBlock(data.activeMission(), pos)
                        || data.optionalMissions().stream().anyMatch(mission ->
                                MissionWorldDirector.isProtectedMissionBlock(mission, pos)));
    }

    public static void onPistonPre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld()) {
            return;
        }

        CampaignSavedData data = CampaignSavedData.get(level.getServer());
        if (!anyProtectedMission(data)) {
            return;
        }
        if (anyProtectedBlockAt(data, event.getFaceOffsetPos())) {
            event.setCanceled(true);
            return;
        }
        if (event.getPistonMoveType() == PistonEvent.PistonMoveType.RETRACT
                && event.getState().is(Blocks.PISTON)) {
            // The generic resolver models a sticky retraction. A vanilla
            // normal piston retracts its head but never pulls the next block.
            return;
        }

        // NeoForge builds the resolver with the event's facing and move type,
        // so this covers both extension pushes and sticky-piston retractions.
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }
        if (anyProtectedBlockIn(data, resolver.getToPush())
                || anyProtectedBlockIn(data, resolver.getToDestroy())
                || anyMovesIntoProtectedBlock(data, resolver)) {
            event.setCanceled(true);
        }
    }

    /**
     * Salvage repair interaction: right-clicking a damaged car point records
     * its unique index and swaps the block to the repaired variant. The index
     * set deduplicates, so repeated clicks at one point never count twice.
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld()
                || !(event.getEntity() instanceof ServerPlayer player)
                || player.isSpectator()) {
            return;
        }

        CampaignSavedData data = CampaignSavedData.get(level.getServer());
        if (!CampaignTickGuard.allowsWorldWrite(data, "mission.event.salvage_repair")) {
            return;
        }
        ActiveMission active = data.activeMission();
        if (active != null
                && active.site() != null
                && CriticalMissionItemDirector.controlPos(active).equals(event.getPos())) {
            event.setCanceled(true);
            if (CriticalMissionItemDirector.tryRedeem(
                    data,
                    active,
                    event.getPos(),
                    event.getItemStack())) {
                player.displayClientMessage(
                        Component.translatable("message.lasttrain.critical_item_redeemed"),
                        true);
            }
            return;
        }
        for (ActiveMission mission : data.optionalMissions()) {
            if (mission.type() != MissionType.SALVAGE_CAR
                    || mission.stage() != MissionStage.ACTIVE
                    || !mission.worldPrepared()
                    || mission.site() == null) {
                continue;
            }
            int index = OptionalMissionDirector.salvageDamageIndexAt(mission.site(), event.getPos());
            if (index < 0 || index >= OptionalMissionDirector.SALVAGE_DAMAGE_POINTS) {
                continue;
            }
            event.setCanceled(true);
            if (!level.getBlockState(event.getPos()).is(Blocks.CRACKED_STONE_BRICKS)) {
                return;
            }
            if (data.recordSalvageRepair(mission.id(), index)) {
                level.setBlock(
                        event.getPos(),
                        Blocks.IRON_BLOCK.defaultBlockState(),
                        3);
                data.optionalMission(mission.id())
                        .filter(m -> m.stage() == MissionStage.READY_TO_TURN_IN)
                        .ifPresent(m -> level.getServer().getPlayerList().broadcastSystemMessage(
                                Component.translatable("message.lasttrain.salvage_repaired"),
                                false));
            }
            return;
        }
    }

    private static boolean anyProtectedMission(CampaignSavedData data) {
        if (MissionWorldDirector.hasProtectedMissionBlocks(data.activeMission())) {
            return true;
        }
        return data.optionalMissions().stream()
                .anyMatch(MissionWorldDirector::hasProtectedMissionBlocks);
    }

    private static boolean anyProtectedBlockAt(CampaignSavedData data, net.minecraft.core.BlockPos pos) {
        if (MissionWorldDirector.isProtectedMissionBlock(data.activeMission(), pos)) {
            return true;
        }
        return data.optionalMissions().stream()
                .anyMatch(mission -> MissionWorldDirector.isProtectedMissionBlock(mission, pos));
    }

    private static boolean anyProtectedBlockIn(
            CampaignSavedData data,
            Iterable<net.minecraft.core.BlockPos> positions) {
        for (net.minecraft.core.BlockPos pos : positions) {
            if (anyProtectedBlockAt(data, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMovesIntoProtectedBlock(
            CampaignSavedData data,
            PistonStructureResolver resolver) {
        return MissionWorldDirector.movesIntoProtectedMissionBlock(
                        data.activeMission(),
                        resolver.getToPush(),
                        resolver.getPushDirection())
                || data.optionalMissions().stream().anyMatch(mission ->
                        MissionWorldDirector.movesIntoProtectedMissionBlock(
                                mission,
                                resolver.getToPush(),
                                resolver.getPushDirection()));
    }
}
