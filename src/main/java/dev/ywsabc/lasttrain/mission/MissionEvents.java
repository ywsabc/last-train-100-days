package dev.ywsabc.lasttrain.mission;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** Event-driven mission accounting for entities that may move across chunks. */
public final class MissionEvents {
    private MissionEvents() {
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)
                || !(zombie.level() instanceof ServerLevel level)) {
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
        if (!(event.getEntity() instanceof Zombie zombie)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        String activeTag = null;
        ActiveMission mission = CampaignSavedData.get(level.getServer()).activeMission();
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
}
