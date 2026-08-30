package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignIntegrityPolicy;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionWorldDirector;
import dev.ywsabc.lasttrain.route.RouteDirector;
import dev.ywsabc.lasttrain.text.TranslationKeys;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class CampaignEvents {
    private static Vec3 lastTrainPosition;
    private static int lastTrainPositionTick;
    private static TrainRecoveryPolicy.Directive lastTrainDirective;

    private CampaignEvents() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        CampaignSavedData data = CampaignSavedData.get(server);
        data.initialize(server.overworld().getSeed());
        WorldBootstrap.ensureStarterStation(server.overworld(), data);
        SimurailTrainBootstrap.ensureLayout(server.overworld(), data);
        IntegrationBridge.syncCampaignNumbers(server, data);
        LastTrain.LOGGER.info(
                "Loaded campaign {} (status={}, day={}, route={})",
                data.campaignId(),
                data.status(),
                data.day(),
                data.routeSegment());
        CampaignIntegrityPolicy.Report integrity = CampaignIntegrityPolicy.audit(data);
        if (!integrity.issues().isEmpty()) {
            // 加载时只报告跨字段矛盾，不擅自修复或删除玩家存档；管理员可用
            // /lasttrain validate save 查看带本地化说明的完整列表。
            LastTrain.LOGGER.warn(
                    "Campaign save integrity check found {} error(s) and {} warning(s): {}",
                    integrity.errors(),
                    integrity.warnings(),
                    integrity.issues());
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        CampaignSavedData data = CampaignSavedData.get(server);
        CampaignTickGuard.run(
                data,
                "campaign.simurail",
                () -> SimurailTrainBootstrap.tick(
                        server.overworld(), data, server.getTickCount()));

        int activePlayers = (int) server.getPlayerList().getPlayers().stream()
                .filter(player -> !player.isSpectator())
                .count();
        long logicalTick = server.overworld().getGameTime();
        ServerPlayer captain = data.captainId() == null
                ? null
                : server.getPlayerList().getPlayer(data.captainId());
        boolean captainOnline = captain != null && !captain.isSpectator();
        CampaignTickGuard.run(
                data,
                "campaign.captain",
                () -> data.observeCaptainOnline(captainOnline, logicalTick));
        CampaignTickGuard.run(
                data,
                "campaign.vote",
                () -> data.expirePendingVote(logicalTick));
        CampaignTickGuard.run(
                data,
                "campaign.train_recovery",
                () -> observeTrainRecovery(server, data, activePlayers));
        if (!ServerActivityPolicy.shouldRunCampaignWorldDirectors(activePlayers)) {
            return;
        }

        // 路线落块、任务实体、奖励箱与清理队列都属于世界副作用；无人在线时
        // 整体暂停，避免专服空转期间悄悄改变现场。载具恢复探测仍在上方执行，
        // 以便依赖丢失能及时进入 SAFE_MODE。
        CampaignTickGuard.run(
                data,
                "campaign.route_director",
                () -> RouteDirector.tick(server, data, server.getTickCount()));
        CampaignTickGuard.run(
                data,
                "campaign.mission_director",
                () -> MissionWorldDirector.tick(server, data, server.getTickCount()));

        if (data.status() == CampaignStatus.NOT_STARTED) {
            CampaignTickGuard.run(
                    data,
                    "campaign.auto_start",
                    () -> tryAutoStart(server, data, activePlayers));
        }

        CampaignSavedData.TickOutcome outcome = CampaignTickGuard.call(
                data,
                "campaign.saved_data_tick",
                () -> data.tick(activePlayers),
                CampaignSavedData.TickOutcome.NONE);
        if (outcome != CampaignSavedData.TickOutcome.NONE
                || server.getTickCount() % 200 == 0) {
            CampaignTickGuard.run(
                    data,
                    "campaign.integration_sync",
                    () -> IntegrationBridge.syncCampaignNumbers(server, data));
        }
        switch (outcome) {
            case DAY_ADVANCED -> broadcast(server, Component.translatable("message.lasttrain.day_advanced", data.day()));
            case DAY_ADVANCED_WITH_MISSION -> {
                broadcast(server, Component.translatable("message.lasttrain.day_advanced", data.day()));
                broadcastMission(server, data.activeMission());
            }
            case DAY_ADVANCED_WITH_FINALE -> {
                broadcast(server, Component.translatable("message.lasttrain.day_advanced", data.day()));
                broadcastFinale(server, data.activeMission());
            }
            case FINALE_MISSION_STARTED -> broadcastFinale(server, data.activeMission());
            case SIEGE_TRIGGERED -> broadcastSiege(server, data.activeMission());
            case FINAL_DAY_ELAPSED ->
                    broadcast(server, Component.translatable("message.lasttrain.final_day_elapsed"));
            case CAMPAIGN_COMPLETED ->
                    broadcast(server, Component.translatable("message.lasttrain.campaign_completed"));
            case NONE -> {
            }
        }
    }

    private static void tryAutoStart(
            MinecraftServer server,
            CampaignSavedData data,
            int activePlayers) {
        UUID trainId = data.starterTrainSublevelId();
        boolean trainLocated = trainId != null
                && SableTrainTracker.position(server.overworld(), trainId).isPresent();
        UUID firstStarter = server.getPlayerList().getPlayers().stream()
                .filter(player -> !player.isSpectator())
                .map(player -> player.getUUID())
                .findFirst()
                .orElse(null);
        if (firstStarter != null
                && CampaignStartPolicy.shouldAutoStart(
                        activePlayers > 0,
                        data.starterStationBuilt(),
                        data.starterTrainAssembled(),
                        trainId != null,
                        trainLocated)
                && data.start(firstStarter)) {
            IntegrationBridge.syncCampaignNumbers(server, data);
            broadcast(server, Component.translatable("message.lasttrain.campaign_started"));
        }
    }

    /**
     * Feeds world-side train facts into the recovery policy every tick. The
     * position cache lives here because it is a per-session observation, not
     * campaign state; safe-mode transitions inside the saved data still apply
     * while nobody is online.
     */
    private static void observeTrainRecovery(
            MinecraftServer server,
            CampaignSavedData data,
            int activePlayers) {
        UUID trainId = data.starterTrainSublevelId();
        boolean referenceKnown = trainId != null;
        Optional<Vec3> located = trainId == null
                ? Optional.empty()
                : SableTrainTracker.position(server.overworld(), trainId);

        boolean moving = false;
        boolean playerInDanger = false;
        if (located.isPresent()) {
            Vec3 position = located.orElseThrow();
            int currentTick = server.getTickCount();
            moving = lastTrainPosition != null
                    && currentTick - lastTrainPositionTick == 1
                    && TrainRecoveryPolicy.isMoving(
                            position.distanceToSqr(lastTrainPosition));
            lastTrainPosition = position;
            lastTrainPositionTick = currentTick;
            playerInDanger = server.getPlayerList().getPlayers().stream()
                    .anyMatch(player -> !player.isSpectator()
                            && TrainRecoveryPolicy.playerInDanger(
                                    player.distanceToSqr(position)));
        } else {
            lastTrainPosition = null;
        }

        TrainRecoveryPolicy.Directive directive = data.observeTrain(
                SimurailTrainBootstrap.hasVehicleStack(),
                referenceKnown,
                located.isPresent(),
                moving,
                playerInDanger,
                activePlayers);
        if (directive != lastTrainDirective) {
            lastTrainDirective = directive;
            LastTrain.LOGGER.info("Train recovery assessment: {}", directive);
        }
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private static void broadcastMission(MinecraftServer server, ActiveMission mission) {
        if (mission == null) {
            return;
        }
        broadcast(server, Component.translatable(
                "message.lasttrain.mission_generated",
                Component.translatable(TranslationKeys.mission(mission.type())),
                mission.routeSegment()));
    }

    private static void broadcastFinale(MinecraftServer server, ActiveMission mission) {
        if (mission == null) {
            return;
        }
        broadcast(server, Component.translatable(
                "message.lasttrain.finale_started",
                mission.target(),
                mission.routeSegment()));
    }

    private static void broadcastSiege(MinecraftServer server, ActiveMission mission) {
        if (mission == null) {
            return;
        }
        broadcast(server, Component.translatable(
                "message.lasttrain.siege_triggered",
                mission.target(),
                mission.routeSegment()));
    }
}
