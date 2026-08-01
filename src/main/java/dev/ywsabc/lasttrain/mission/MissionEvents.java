package dev.ywsabc.lasttrain.mission;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/** Event-driven mission accounting for entities that may move across chunks. */
public final class MissionEvents {
    private MissionEvents() {
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)
                || !(zombie.level() instanceof ServerLevel level)
                || !(event.getSource().getEntity() instanceof ServerPlayer)) {
            return;
        }

        MinecraftServer server = level.getServer();
        CampaignSavedData data = CampaignSavedData.get(server);
        ActiveMission mission = data.activeMission();
        if (mission == null
                || mission.type() != MissionType.ZOMBIE_BLOCKADE
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
                            Component.translatable(
                                    "mission.lasttrain." + mission.type().serializedName())),
                    false);
        }
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ActiveMission mission = CampaignSavedData.get(level.getServer()).activeMission();
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

        if (!(event.getEntity() instanceof Zombie zombie)) {
            return;
        }
        String activeTag = null;
        if (mission != null && mission.type() == MissionType.ZOMBIE_BLOCKADE) {
            activeTag = MissionWorldDirector.missionEntityTag(mission);
        }
        for (String tag : zombie.getTags()) {
            if (MissionWorldDirector.isMissionEntityTag(tag) && !tag.equals(activeTag)) {
                zombie.discard();
                return;
            }
        }
    }

    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld()) {
            return;
        }

        ActiveMission mission = CampaignSavedData.get(level.getServer()).activeMission();
        if (MissionWorldDirector.isProtectedMissionBlock(mission, event.getPos())) {
            event.setCanceled(true);
        }
    }

    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld()) {
            return;
        }

        ActiveMission mission = CampaignSavedData.get(level.getServer()).activeMission();
        event.getAffectedBlocks().removeIf(
                pos -> MissionWorldDirector.isProtectedMissionBlock(mission, pos));
    }

    public static void onPistonPre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld()) {
            return;
        }

        ActiveMission mission = CampaignSavedData.get(level.getServer()).activeMission();
        if (!MissionWorldDirector.hasProtectedMissionBlocks(mission)) {
            return;
        }
        if (MissionWorldDirector.isProtectedMissionBlock(
                mission,
                event.getFaceOffsetPos())) {
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
        if (MissionWorldDirector.containsProtectedMissionBlock(mission, resolver.getToPush())
                || MissionWorldDirector.containsProtectedMissionBlock(
                        mission,
                        resolver.getToDestroy())
                || MissionWorldDirector.movesIntoProtectedMissionBlock(
                        mission,
                        resolver.getToPush(),
                        resolver.getPushDirection())) {
            event.setCanceled(true);
        }
    }
}
