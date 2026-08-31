package dev.ywsabc.lasttrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.campaign.CampaignDiagnostics;
import dev.ywsabc.lasttrain.campaign.CampaignIntegrityPolicy;
import dev.ywsabc.lasttrain.campaign.CampaignMode;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.campaign.TeamPermissionPolicy;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionBriefing;
import dev.ywsabc.lasttrain.mission.MissionCommandPolicy;
import dev.ywsabc.lasttrain.mission.MissionFallbackPolicy;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.mission.MissionWorldDirector;
import dev.ywsabc.lasttrain.server.IntegrationBridge;
import dev.ywsabc.lasttrain.text.TranslationKeys;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class LastTrainCommands {
    private LastTrainCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("lasttrain")
                .then(Commands.literal("status")
                        .executes(LastTrainCommands::status)
                        .then(Commands.literal("detail")
                                .executes(LastTrainCommands::detailedStatus)))
                .then(Commands.literal("validate")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("save")
                                .executes(LastTrainCommands::validateSave)))
                .then(Commands.literal("mode")
                        .then(Commands.literal("endless")
                                .executes(LastTrainCommands::enableEndlessMode)))
                .then(Commands.literal("start")
                        .requires(source -> source.hasPermission(2))
                        .executes(LastTrainCommands::start))
                .then(Commands.literal("team")
                        .then(Commands.literal("transfer")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(LastTrainCommands::transferCaptain)))
                        .then(Commands.literal("claim")
                                .executes(LastTrainCommands::claimCaptain))
                        .then(Commands.literal("vote")
                                .then(Commands.literal("start")
                                        .then(Commands.argument("operation", StringArgumentType.word())
                                                .suggests((context, builder) -> {
                                                    for (TeamPermissionPolicy.Operation operation
                                                            : TeamPermissionPolicy.Operation.values()) {
                                                        builder.suggest(operation.serializedName());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(LastTrainCommands::startTeamVote)))
                                .then(Commands.literal("yes")
                                        .executes(context -> castTeamVote(context, true)))
                                .then(Commands.literal("no")
                                        .executes(context -> castTeamVote(context, false))))
                        // Short alias is useful for command users and keeps
                        // the documented /team vote <yes|no> path intact.
                        .then(Commands.literal("vote_start")
                                .then(Commands.argument("operation", StringArgumentType.word())
                                        .executes(LastTrainCommands::startTeamVote))))
                .then(Commands.literal("advance_day")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 100))
                                .executes(LastTrainCommands::advanceDay)))
                .then(Commands.literal("route")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("advance")
                                .then(Commands.argument("segments", IntegerArgumentType.integer(1, 10_000))
                                        .executes(LastTrainCommands::advanceRoute))))
                .then(Commands.literal("mission")
                        .then(Commands.literal("status")
                                .executes(LastTrainCommands::missionStatus))
                        .then(Commands.literal("create")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            Arrays.stream(MissionType.values())
                                                    .map(MissionType::serializedName)
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(LastTrainCommands::createMission)))
                        .then(Commands.literal("progress")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10_000))
                                        .executes(LastTrainCommands::progressMission)))
                        .then(Commands.literal("turn_in")
                                .executes(LastTrainCommands::turnInMission))
                        .then(Commands.literal("fail")
                                .executes(LastTrainCommands::failMission))
                        .then(Commands.literal("clear")
                                .requires(source -> source.hasPermission(2))
                                .executes(LastTrainCommands::clearMission))
                        .then(Commands.literal("accept")
                                .executes(context -> answerProposal(context, null, null, true))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(context -> answerProposal(
                                                context,
                                                StringArgumentType.getString(context, "id"),
                                                null,
                                                true))
                                        .then(Commands.argument("revision", IntegerArgumentType.integer(0))
                                                .executes(context -> answerProposal(
                                                        context,
                                                        StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "revision"),
                                                        true)))))
                        .then(Commands.literal("reject")
                                .executes(context -> answerProposal(context, null, null, false))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(context -> answerProposal(
                                                context,
                                                StringArgumentType.getString(context, "id"),
                                                null,
                                                false))
                                        .then(Commands.argument("revision", IntegerArgumentType.integer(0))
                                                .executes(context -> answerProposal(
                                                        context,
                                                        StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "revision"),
                                                        false)))))
                        .then(Commands.literal("skip")
                                .executes(context -> skipOptional(context, null, null))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(context -> skipOptional(
                                                context,
                                                StringArgumentType.getString(context, "id"),
                                                null))
                                        .then(Commands.argument("revision", IntegerArgumentType.integer(0))
                                                .executes(context -> skipOptional(
                                                        context,
                                                        StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "revision")))))))
                .then(Commands.literal("recover")
                        .then(Commands.literal("status")
                                .executes(LastTrainCommands::recoverStatus))
                        .then(Commands.literal("train")
                                .executes(LastTrainCommands::recoverTrain))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        CampaignDiagnostics diagnostics = diagnostics(context, data);
        context.getSource().sendSuccess(
                () -> statusMessage(diagnostics),
                false);
        CampaignIntegrityPolicy.Report integrity = CampaignIntegrityPolicy.audit(data);
        if (!integrity.issues().isEmpty()) {
            sendIntegrityReport(context, integrity);
        }
        return data.day();
    }

    static String modeTranslationKey(CampaignMode mode) {
        CampaignMode safeMode = mode == null ? CampaignMode.STORY_100_DAYS : mode;
        return TranslationKeys.campaignMode(safeMode);
    }

    private static Component statusMessage(CampaignDiagnostics diagnostics) {
        return Component.translatable(
                "command.lasttrain.status",
                diagnostics.day(),
                diagnostics.mode() == CampaignMode.ENDLESS ? "∞" : CampaignSavedData.FINAL_DAY,
                Component.translatable(modeTranslationKey(diagnostics.mode())),
                Component.translatable(TranslationKeys.campaignStatus(diagnostics.status())),
                diagnostics.routeSegment(),
                diagnostics.threat(),
                diagnostics.infectionStage().index(),
                Component.translatable(
                        TranslationKeys.infectionStage(diagnostics.infectionStage())),
                diagnostics.effectivePlayers(),
                diagnostics.onlinePlayers(),
                diagnostics.attention(),
                Component.translatable(TranslationKeys.attention(diagnostics.attentionLevel())),
                diagnostics.pursuitDistance());
    }

    /** 多行统计视图保持只读；先采样一次，避免输出跨 tick 的混合状态。 */
    private static int detailedStatus(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        CampaignDiagnostics diagnostics = diagnostics(context, data);
        context.getSource().sendSuccess(() -> statusMessage(diagnostics), false);
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.status.detail.clock",
                        diagnostics.activeTicksIntoDay(),
                        CampaignSavedData.DEFAULT_ACTIVE_TICKS_PER_DAY,
                        diagnostics.totalActiveTicks()),
                false);
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.status.detail.route",
                        diagnostics.routeSegment(),
                        diagnostics.expectedRouteSegment(),
                        diagnostics.routeDeltaFromExpected(),
                        diagnostics.generatedRouteSegment(),
                        diagnostics.generatedLead(),
                        diagnostics.plannedRouteSegments(),
                        Component.translatable(TranslationKeys.pace(diagnostics.pace()))),
                false);
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.status.detail.missions",
                        diagnostics.activeMission().isPresent() ? 1 : 0,
                        diagnostics.optionalMissions(),
                        diagnostics.proposalPending() ? 1 : 0,
                        diagnostics.pendingRewards(),
                        diagnostics.claimedRewards(),
                        diagnostics.pendingCleanups()),
                false);
        diagnostics.activeMission().ifPresent(mission -> context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.status.detail.active_mission",
                        Component.translatable(TranslationKeys.mission(mission.type())),
                        mission.id(),
                        Component.translatable(TranslationKeys.missionStage(mission.stage())),
                        Component.translatable(TranslationKeys.missionPhase(mission.phase())),
                        mission.progress(),
                        mission.target(),
                        mission.routeSegment()),
                false));
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.status.detail.recovery",
                        Component.translatable(TranslationKeys.booleanValue(
                                diagnostics.starterTrainAssembled())),
                        Component.translatable(TranslationKeys.booleanValue(
                                diagnostics.starterTrainIdentityPresent())),
                        diagnostics.trainMissingTicks(),
                        diagnostics.trainImmobileTicks(),
                        diagnostics.rescueCount(),
                        diagnostics.rescueAnchorSegment()),
                false);
        sendIntegrityReport(context, CampaignIntegrityPolicy.audit(data));
        return diagnostics.day();
    }

    private static int validateSave(CommandContext<CommandSourceStack> context) {
        CampaignIntegrityPolicy.Report report = CampaignIntegrityPolicy.audit(data(context));
        sendIntegrityReport(context, report);
        return report.healthy() ? 1 : 0;
    }

    private static void sendIntegrityReport(
            CommandContext<CommandSourceStack> context,
            CampaignIntegrityPolicy.Report report) {
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.validate.summary",
                        report.errors(),
                        report.warnings()),
                false);
        for (CampaignIntegrityPolicy.Issue issue : report.issues()) {
            context.getSource().sendSuccess(
                    () -> Component.translatable(
                            "command.lasttrain.validate.issue",
                            Component.translatable(
                                    TranslationKeys.integritySeverity(issue.severity())),
                            Component.translatable(TranslationKeys.integrity(issue.code())),
                            issue.detail()),
                    false);
        }
    }

    private static CampaignDiagnostics diagnostics(
            CommandContext<CommandSourceStack> context,
            CampaignSavedData data) {
        int onlinePlayers = (int) context.getSource().getServer().getPlayerList()
                .getPlayers().stream()
                .filter(player -> !player.isSpectator())
                .count();
        return CampaignDiagnostics.snapshot(data, onlinePlayers);
    }

    private static int enableEndlessMode(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        if (!mayPerformTeamOperation(
                context,
                data,
                TeamPermissionPolicy.Operation.ENABLE_ENDLESS_MODE)) {
            return 0;
        }
        if (data.mode() == CampaignMode.ENDLESS) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mode.already_endless"));
            return 0;
        }
        if (data.status() != CampaignStatus.COMPLETED) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mode.requires_completed"));
            return 0;
        }
        if (!data.enableEndlessMode()) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mode.switch_refused"));
            return 0;
        }
        IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.mode.endless_enabled"),
                true);
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        ServerPlayer starter = player(context);
        if (starter == null) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.requires_player"));
            return 0;
        }
        if (starter.isSpectator()) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.requires_team_member"));
            return 0;
        }
        if (data.status() != CampaignStatus.NOT_STARTED) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.already_started"));
            return 0;
        }
        // A first player can issue the operator-gated bootstrap command before
        // the login event has completed. Registration is idempotent and makes
        // the identity check explicit before the campaign is started.
        data.registerTeamMember(starter.getUUID());
        if (!data.start(starter.getUUID())) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.already_started"));
            return 0;
        }
        IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
        context.getSource().sendSuccess(() -> Component.translatable("command.lasttrain.started"), true);
        return 1;
    }

    private static int advanceDay(CommandContext<CommandSourceStack> context) {
        int days = IntegerArgumentType.getInteger(context, "days");
        CampaignSavedData data = data(context);
        if (!requireAdminTeamPlayer(context, data)) {
            return 0;
        }
        data.advanceDays(days);
        IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.day_set", data.day()),
                true);
        return data.day();
    }

    private static int advanceRoute(CommandContext<CommandSourceStack> context) {
        int segments = IntegerArgumentType.getInteger(context, "segments");
        CampaignSavedData data = data(context);
        if (!requireAdminTeamPlayer(context, data)) {
            return 0;
        }
        if (!data.advanceRoute(segments)) {
            context.getSource().sendFailure(
                    Component.translatable(
                            data.routeSafetyLimitReached()
                                    ? "command.lasttrain.route.safety_limit"
                                    : "command.lasttrain.route.no_progress"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.route_advanced", data.routeSegment()),
                true);
        return data.routeSegment();
    }

    private static int missionStatus(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        ServerPlayer player = player(context);
        if (player == null) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.requires_player"));
            return 0;
        }
        if (!MissionCommandPolicy.mayAnswer(actor(context, player, data))) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.requires_team_member"));
            return 0;
        }
        ActiveMission mission = data.activeMission();
        if (mission == null && data.proposedMission() == null && data.optionalMissions().isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.translatable("command.lasttrain.mission.none"),
                    false);
            return 0;
        }
        if (mission != null) {
            context.getSource().sendSuccess(() -> missionSummary(mission), false);
        }
        ActiveMission proposal = data.proposedMission();
        if (proposal != null) {
            context.getSource().sendSuccess(() -> proposalSummary(proposal), false);
        }
        for (ActiveMission optional : data.optionalMissions()) {
            context.getSource().sendSuccess(() -> missionSummary(optional), false);
        }
        return 1;
    }

    private static int createMission(CommandContext<CommandSourceStack> context) {
        String rawType = StringArgumentType.getString(context, "type");
        MissionType type = MissionType.parse(rawType).orElse(null);
        if (type == null) {
            String choices = Arrays.stream(MissionType.values())
                    .map(MissionType::serializedName)
                    .collect(Collectors.joining(", "));
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.invalid_type", choices));
            return 0;
        }

        CampaignSavedData data = data(context);
        if (!requireAdminTeamPlayer(context, data)) {
            return 0;
        }
        if (type.category() == MissionType.Category.OPTIONAL) {
            if (!data.proposeOptionalMission(type)) {
                context.getSource().sendFailure(
                        Component.translatable("command.lasttrain.mission.propose_refused"));
                return 0;
            }
            context.getSource().sendSuccess(
                    () -> proposalSummary(data.proposedMission()),
                    true);
            return 1;
        }
        if (!data.createMission(type)) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.exists"));
            return 0;
        }
        context.getSource().sendSuccess(() -> missionSummary(data.activeMission()), true);
        return 1;
    }

    private static int progressMission(CommandContext<CommandSourceStack> context) {
        int amount = IntegerArgumentType.getInteger(context, "amount");
        CampaignSavedData data = data(context);
        if (!requireAdminTeamPlayer(context, data)) {
            return 0;
        }
        if (!data.addMissionProgress(amount)) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.no_progress"));
            return 0;
        }
        context.getSource().sendSuccess(() -> missionSummary(data.activeMission()), true);
        return data.activeMission().progress();
    }

    private static int turnInMission(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        if (requireTeamPlayer(context, data) == null) {
            return 0;
        }
        ActiveMission mission = data.activeMission();
        if (mission == null || mission.stage() != MissionStage.READY_TO_TURN_IN) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.not_ready"));
            return 0;
        }
        if (!MissionWorldDirector.clearMissionWorld(
                context.getSource().getServer().overworld(),
                mission)) {
            context.getSource().sendFailure(missionSiteUnavailable(mission));
            return 0;
        }
        if (!data.turnInMission()) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.not_ready"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.mission.completed"),
                true);
        if (data.status() == CampaignStatus.COMPLETED) {
            context.getSource().getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.lasttrain.campaign_completed"),
                    false);
        }
        IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
        return 1;
    }

    private static int failMission(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        if (!mayPerformTeamOperation(
                context,
                data,
                TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION)) {
            return 0;
        }
        ActiveMission mission = data.activeMission();
        if (mission == null) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.none"));
            return 0;
        }
        if (data.isFinaleMission(mission)) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.finale_cannot_fail"));
            return 0;
        }
        if (!MissionWorldDirector.clearMissionWorld(
                context.getSource().getServer().overworld(),
                mission)) {
            context.getSource().sendFailure(missionSiteUnavailable(mission));
            return 0;
        }
        data.failMission(MissionFallbackPolicy.THREAT_PENALTY);
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.mission.failed",
                        data.threat()),
                true);
        IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
        return 1;
    }

    private static int clearMission(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        if (!requireAdminTeamPlayer(context, data)) {
            return 0;
        }
        ActiveMission mission = data.activeMission();
        if (mission == null) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.none"));
            return 0;
        }
        if (!MissionWorldDirector.clearMissionWorld(
                context.getSource().getServer().overworld(),
                mission)) {
            context.getSource().sendFailure(missionSiteUnavailable(mission));
            return 0;
        }
        data.clearMission();
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.mission.cleared"),
                true);
        return 1;
    }

    // ------------------------------------------------------------------
    // Team captain, permission gates and votes
    // ------------------------------------------------------------------

    private static int transferCaptain(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        ServerPlayer requester = requireTeamPlayer(context, data);
        if (requester == null) {
            return 0;
        }
        String rawTarget = StringArgumentType.getString(context, "player");
        ServerPlayer target = findOnlinePlayer(
                context.getSource().getServer(),
                rawTarget);
        UUID targetId = target == null ? parseId(rawTarget) : target.getUUID();
        if (targetId == null
                || !data.isTeamMember(targetId)
                || target != null && target.isSpectator()) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.invalid_target"));
            return 0;
        }
        if (!data.transferCaptain(
                requester.getUUID(),
                targetId,
                context.getSource().getServer().overworld().getGameTime(),
                target != null)) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.requires_captain"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.team.transferred",
                        target == null ? rawTarget : target.getGameProfile().getName()),
                true);
        return 1;
    }

    private static int claimCaptain(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        ServerPlayer requester = requireTeamPlayer(context, data);
        if (requester == null) {
            return 0;
        }
        if (!data.claimCaptain(
                requester.getUUID(),
                context.getSource().getServer().overworld().getGameTime(),
                TeamPermissionPolicy.Config.defaults())) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.claim_refused"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.team.claimed"),
                true);
        return 1;
    }

    private static int startTeamVote(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        ServerPlayer requester = requireTeamPlayer(context, data);
        if (requester == null) {
            return 0;
        }
        TeamPermissionPolicy.Operation operation = TeamPermissionPolicy.Operation.parse(
                StringArgumentType.getString(context, "operation"));
        if (operation == null) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.invalid_operation"));
            return 0;
        }
        TeamPermissionPolicy.Config config = TeamPermissionPolicy.Config.defaults();
        TeamPermissionPolicy.VoteResult result = data.startVote(
                operation,
                requester.getUUID(),
                permissionLevel(context),
                true,
                requester.isSpectator(),
                context.getSource().getServer().overworld().getGameTime(),
                config);
        switch (result) {
            case STARTED -> {
                context.getSource().sendSuccess(
                        () -> Component.translatable(
                                "command.lasttrain.team.vote_started",
                                operation.serializedName()),
                        true);
                return 1;
            }
            case ALREADY_PENDING -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.vote_pending"));
            case INVALID_OPERATION -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.vote_not_supported"));
            default -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.requires_captain"));
        }
        return 0;
    }

    private static int castTeamVote(
            CommandContext<CommandSourceStack> context,
            boolean yes) {
        CampaignSavedData data = data(context);
        ServerPlayer requester = requireTeamPlayer(context, data);
        if (requester == null) {
            return 0;
        }
        TeamPermissionPolicy.Operation votedOperation = data.pendingVote()
                .map(TeamPermissionPolicy.Vote::operation)
                .orElse(null);
        TeamPermissionPolicy.VoteResult result = data.castVote(
                requester.getUUID(),
                yes,
                context.getSource().getServer().overworld().getGameTime());
        switch (result) {
            case VOTE_RECORDED -> {
                context.getSource().sendSuccess(
                        () -> Component.translatable("command.lasttrain.team.vote_recorded"),
                        false);
                return 1;
            }
            case PASSED -> {
                context.getSource().sendSuccess(
                        () -> Component.translatable("command.lasttrain.team.vote_passed"),
                        true);
                if (votedOperation == TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION) {
                    applyPassedTeamVote(context, data);
                }
                return 1;
            }
            case REJECTED -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.vote_rejected"));
            case EXPIRED -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.vote_expired"));
            case ALREADY_VOTED -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.vote_already_cast"));
            case NO_ACTIVE_VOTE -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.vote_none"));
            default -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.requires_team_member"));
        }
        return 0;
    }

    /** Applies the one P5 vote-backed operation whose world mutation exists. */
    private static void applyPassedTeamVote(
            CommandContext<CommandSourceStack> context,
            CampaignSavedData data) {
        // The durable state machine removes a passed vote, so the command
        // itself remains the final authority for the operation's live target.
        // A future operation (for example endless mode in P6) can add its
        // handler here without changing vote accounting.
        ActiveMission mission = data.activeMission();
        if (mission == null
                || data.isFinaleMission(mission)
                || !MissionWorldDirector.clearMissionWorld(
                        context.getSource().getServer().overworld(),
                        mission)) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.vote_target_unavailable"));
            return;
        }
        if (data.failMission(MissionFallbackPolicy.THREAT_PENALTY)) {
            IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
            context.getSource().sendSuccess(
                    () -> Component.translatable(
                            "command.lasttrain.mission.failed",
                            data.threat()),
                    true);
        }
    }

    private static boolean mayPerformTeamOperation(
            CommandContext<CommandSourceStack> context,
            CampaignSavedData data,
            TeamPermissionPolicy.Operation operation) {
        ServerPlayer requester = requireTeamPlayer(context, data);
        if (requester == null) {
            return false;
        }
        TeamPermissionPolicy.Decision decision = TeamPermissionPolicy.decide(
                operation,
                teamRequester(context, requester),
                teamState(context.getSource().getServer(), data),
                TeamPermissionPolicy.Config.defaults());
        if (decision == TeamPermissionPolicy.Decision.ALLOW) {
            return true;
        }
        context.getSource().sendFailure(Component.translatable(
                decision == TeamPermissionPolicy.Decision.NEED_VOTE
                        ? "command.lasttrain.team.requires_vote"
                        : "command.lasttrain.team.requires_captain"));
        return false;
    }

    private static ServerPlayer requireTeamPlayer(
            CommandContext<CommandSourceStack> context,
            CampaignSavedData data) {
        ServerPlayer requester = player(context);
        if (requester == null) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.requires_player"));
            return null;
        }
        if (requester.isSpectator() || !data.isTeamMember(requester.getUUID())) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.requires_team_member"));
            return null;
        }
        return requester;
    }

    private static boolean requireAdminTeamPlayer(
            CommandContext<CommandSourceStack> context,
            CampaignSavedData data) {
        if (requireTeamPlayer(context, data) == null) {
            return false;
        }
        if (!context.getSource().hasPermission(TeamPermissionPolicy.ADMIN_PERMISSION_LEVEL)) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.team.requires_captain"));
            return false;
        }
        return true;
    }

    private static ServerPlayer findOnlinePlayer(MinecraftServer server, String rawNameOrId) {
        if (rawNameOrId == null || rawNameOrId.isBlank()) {
            return null;
        }
        try {
            UUID id = UUID.fromString(rawNameOrId);
            return server.getPlayerList().getPlayer(id);
        } catch (IllegalArgumentException ignored) {
            return server.getPlayerList().getPlayers().stream()
                    .filter(candidate -> candidate.getGameProfile().getName().equalsIgnoreCase(rawNameOrId))
                    .findFirst()
                    .orElse(null);
        }
    }

    // ------------------------------------------------------------------
    // Optional mission answers
    // ------------------------------------------------------------------

    private static int answerProposal(
            CommandContext<CommandSourceStack> context,
            String rawId,
            Integer revision,
            boolean accept) {
        CampaignSavedData data = data(context);
        ServerPlayer player = player(context);
        if (player == null) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.requires_player"));
            return 0;
        }
        if (!MissionCommandPolicy.mayAnswer(actor(context, player, data))) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.requires_team_member"));
            return 0;
        }
        UUID givenId = parseId(rawId);
        if (rawId != null && givenId == null) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.invalid_id"));
            return 0;
        }

        ActiveMission proposal = data.proposedMission();
        Component missionName = proposal == null
                ? Component.translatable("command.lasttrain.mission.none")
                : Component.translatable(TranslationKeys.mission(proposal.type()));
        CampaignSavedData.ProposalAnswer result =
                accept ? data.acceptProposal(givenId, revision) : data.rejectProposal(givenId, revision);
        switch (result) {
            case ACCEPTED -> {
                context.getSource().sendSuccess(
                        () -> Component.translatable(
                                "command.lasttrain.mission.accepted",
                                missionName),
                        true);
                return 1;
            }
            case SKIPPED -> {
                context.getSource().sendSuccess(
                        () -> Component.translatable(
                                "command.lasttrain.mission.rejected",
                                missionName),
                        true);
                return 1;
            }
            case NOT_FOUND -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.no_proposal"));
            case STALE_ID -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.stale_id"));
            case STALE_REVISION -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.stale_revision"));
            case WRONG_STAGE -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.not_proposed"));
            case SLOTS_FULL -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.optional_slots_full"));
            case OK_FOR_APPLY -> {
            }
        }
        return 0;
    }

    private static int skipOptional(
            CommandContext<CommandSourceStack> context,
            String rawId,
            Integer revision) {
        CampaignSavedData data = data(context);
        ServerPlayer player = player(context);
        if (player == null) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.requires_player"));
            return 0;
        }
        if (!MissionCommandPolicy.maySkip(actor(context, player, data))) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.requires_captain"));
            return 0;
        }
        UUID givenId = parseId(rawId);
        if (rawId != null && givenId == null) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.invalid_id"));
            return 0;
        }

        Component targetName = skipTargetName(data, givenId);
        CampaignSavedData.OptionalSkipResult result = data.skipOptionalMission(givenId, revision);
        switch (result) {
            case SKIPPED -> {
                Component name = targetName != null
                        ? targetName
                        : Component.translatable("command.lasttrain.mission.none");
                context.getSource().sendSuccess(
                        () -> Component.translatable("command.lasttrain.mission.skipped", name),
                        true);
                return 1;
            }
            case NOT_FOUND -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.no_optional"));
            case AMBIGUOUS -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.ambiguous_optional"));
            case STALE_REVISION -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.stale_revision"));
            case WRONG_STAGE -> context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.optional_not_skippable"));
        }
        return 0;
    }

    /** The task shown for confirmation when skipping without an id. */
    private static Component skipTargetName(CampaignSavedData data, UUID givenId) {
        if (givenId != null) {
            return data.optionalMission(givenId)
                    .map(mission -> Component.translatable(TranslationKeys.mission(mission.type())))
                    .orElse(null);
        }
        java.util.List<ActiveMission> skippable = data.optionalMissions().stream()
                .filter(mission -> mission.stage() == MissionStage.ACTIVE
                        || mission.stage() == MissionStage.READY_TO_TURN_IN)
                .toList();
        if (skippable.size() != 1) {
            return null;
        }
        return Component.translatable(
                TranslationKeys.mission(skippable.get(0).type()));
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> context) {
        return context.getSource().getEntity() instanceof ServerPlayer player ? player : null;
    }

    private static int permissionLevel(CommandContext<CommandSourceStack> context) {
        return context.getSource().hasPermission(TeamPermissionPolicy.ADMIN_PERMISSION_LEVEL)
                ? TeamPermissionPolicy.ADMIN_PERMISSION_LEVEL
                : 0;
    }

    private static TeamPermissionPolicy.Requester teamRequester(
            CommandContext<CommandSourceStack> context,
            ServerPlayer player) {
        return new TeamPermissionPolicy.Requester(
                player.getUUID(),
                true,
                player.isSpectator(),
                permissionLevel(context));
    }

    private static TeamPermissionPolicy.TeamState teamState(
            MinecraftServer server,
            CampaignSavedData data) {
        ServerPlayer captain = data.captainId() == null
                ? null
                : server.getPlayerList().getPlayer(data.captainId());
        boolean captainOnline = captain != null && !captain.isSpectator();
        return new TeamPermissionPolicy.TeamState(
                data.captainId(),
                data.teamMembers(),
                captainOnline);
    }

    private static MissionCommandPolicy.Actor actor(
            CommandContext<CommandSourceStack> context,
            ServerPlayer player,
            CampaignSavedData data) {
        return new MissionCommandPolicy.Actor(
                true,
                player.isSpectator(),
                data.isTeamMember(player.getUUID()),
                context.getSource().hasPermission(MissionCommandPolicy.SKIP_PERMISSION_LEVEL)
                        ? MissionCommandPolicy.SKIP_PERMISSION_LEVEL
                        : 0,
                data.isCaptain(player.getUUID()));
    }

    private static UUID parseId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawId.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int recoverStatus(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.recover.status",
                        data.trainMissingTicks(),
                        data.trainImmobileTicks(),
                        data.rescueCount(),
                        data.lastRescueDay(),
                        data.rescueAnchorSegment()),
                false);
        return 1;
    }

    private static int recoverTrain(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        if (!mayPerformTeamOperation(
                context,
                data,
                TeamPermissionPolicy.Operation.TRAIN_RECOVERY)) {
            return 0;
        }
        if (!data.applyTrainRescue()) {
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.recover.refused"));
            return 0;
        }
        IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.recover.applied",
                        data.rescueCount(),
                        data.rescueAnchorSegment(),
                        data.attention(),
                        data.threat()),
                true);
        return data.rescueCount();
    }

    private static CampaignSavedData data(CommandContext<CommandSourceStack> context) {
        return CampaignSavedData.get(context.getSource().getServer());
    }

    private static Component missionSummary(ActiveMission mission) {
        return Component.translatable(
                "command.lasttrain.mission.status",
                Component.translatable(TranslationKeys.mission(mission.type())),
                mission.progress(),
                mission.target(),
                mission.routeSegment(),
                Component.translatable(TranslationKeys.missionStage(mission.stage())),
                Component.translatable(TranslationKeys.missionPhase(mission.currentPhase())));
    }

    /** Pre-acceptance briefing: type, risk, reward category and time limit. */
    private static Component proposalSummary(ActiveMission proposal) {
        MissionBriefing briefing = MissionBriefing.of(proposal.type()).orElse(null);
        Component risk = briefing == null
                ? Component.translatable("briefing.lasttrain.risk.none")
                : Component.translatable(TranslationKeys.briefingRisk(briefing.risk()));
        Component reward = briefing == null
                ? Component.translatable("briefing.lasttrain.reward.none")
                : Component.translatable(TranslationKeys.briefingReward(briefing.reward()));
        Component timed = briefing == null
                ? Component.translatable("briefing.lasttrain.timed.none")
                : Component.translatable("briefing.lasttrain.timed.days", briefing.timedDays());
        return Component.translatable(
                "command.lasttrain.mission.proposal",
                Component.translatable(TranslationKeys.mission(proposal.type())),
                proposal.id(),
                proposal.revision(),
                proposal.routeSegment(),
                risk,
                reward,
                timed);
    }

    private static Component missionSiteUnavailable(ActiveMission mission) {
        if (mission.site() == null) {
            return Component.translatable(
                    "command.lasttrain.mission.site_unavailable", "?", "?", "?");
        }
        return Component.translatable(
                "command.lasttrain.mission.site_unavailable",
                mission.site().getX(),
                mission.site().getY(),
                mission.site().getZ());
    }
}
