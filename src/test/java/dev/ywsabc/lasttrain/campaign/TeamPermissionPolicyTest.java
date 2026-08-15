package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamPermissionPolicyTest {
    private static final UUID CAPTAIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final TeamPermissionPolicy.TeamState CAPTAIN_ONLINE =
            new TeamPermissionPolicy.TeamState(CAPTAIN, Set.of(CAPTAIN, MEMBER), true);
    private static final TeamPermissionPolicy.TeamState CAPTAIN_OFFLINE =
            new TeamPermissionPolicy.TeamState(CAPTAIN, Set.of(CAPTAIN, MEMBER), false);

    @Test
    void captainAndLevelTwoAdminMayPerformCaptainGatedOperations() {
        for (TeamPermissionPolicy.Operation operation : new TeamPermissionPolicy.Operation[] {
            TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION,
            TeamPermissionPolicy.Operation.TRAIN_RECOVERY,
            TeamPermissionPolicy.Operation.DISMANTLE_CRITICAL_CONTROL,
            TeamPermissionPolicy.Operation.ENABLE_ENDLESS_MODE
        }) {
            assertEquals(
                    TeamPermissionPolicy.Decision.ALLOW,
                    TeamPermissionPolicy.decide(
                            operation,
                            new TeamPermissionPolicy.Requester(CAPTAIN, true, false, 0),
                            CAPTAIN_ONLINE,
                            TeamPermissionPolicy.Config.defaults()));
            assertEquals(
                    TeamPermissionPolicy.Decision.ALLOW,
                    TeamPermissionPolicy.decide(
                            operation,
                            new TeamPermissionPolicy.Requester(MEMBER, true, false, 2),
                            CAPTAIN_ONLINE,
                            TeamPermissionPolicy.Config.defaults()));
        }
    }

    @Test
    void resetAndCompensationRequireAnInTeamLevelTwoAdmin() {
        for (TeamPermissionPolicy.Operation operation : new TeamPermissionPolicy.Operation[] {
            TeamPermissionPolicy.Operation.RESET_CHAPTER,
            TeamPermissionPolicy.Operation.ADMIN_COMPENSATION
        }) {
            assertEquals(
                    TeamPermissionPolicy.Decision.DENY,
                    TeamPermissionPolicy.decide(
                            operation,
                            new TeamPermissionPolicy.Requester(CAPTAIN, true, false, 0),
                            CAPTAIN_ONLINE,
                            TeamPermissionPolicy.Config.defaults()));
            assertEquals(
                    TeamPermissionPolicy.Decision.ALLOW,
                    TeamPermissionPolicy.decide(
                            operation,
                            new TeamPermissionPolicy.Requester(MEMBER, true, false, 2),
                            CAPTAIN_ONLINE,
                            TeamPermissionPolicy.Config.defaults()));
            assertEquals(
                    TeamPermissionPolicy.Decision.DENY,
                    TeamPermissionPolicy.decide(
                            operation,
                            new TeamPermissionPolicy.Requester(OUTSIDER, true, false, 4),
                            CAPTAIN_ONLINE,
                            TeamPermissionPolicy.Config.defaults()));
        }
    }

    @Test
    void ordinaryMemberNeedsVoteForAbandonButCannotUseCaptainOnlyActions() {
        TeamPermissionPolicy.Requester member =
                new TeamPermissionPolicy.Requester(MEMBER, true, false, 0);

        assertEquals(
                TeamPermissionPolicy.Decision.NEED_VOTE,
                TeamPermissionPolicy.decide(
                        TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION,
                        member,
                        CAPTAIN_ONLINE,
                        TeamPermissionPolicy.Config.defaults()));
        assertEquals(
                TeamPermissionPolicy.Decision.DENY,
                TeamPermissionPolicy.decide(
                        TeamPermissionPolicy.Operation.TRAIN_RECOVERY,
                        member,
                        CAPTAIN_ONLINE,
                TeamPermissionPolicy.Config.defaults()));
    }

    @Test
    void ordinaryMemberIsDeniedForEveryOtherHighImpactOperation() {
        TeamPermissionPolicy.Requester member =
                new TeamPermissionPolicy.Requester(MEMBER, true, false, 0);
        for (TeamPermissionPolicy.Operation operation : new TeamPermissionPolicy.Operation[] {
            TeamPermissionPolicy.Operation.TRAIN_RECOVERY,
            TeamPermissionPolicy.Operation.DISMANTLE_CRITICAL_CONTROL,
            TeamPermissionPolicy.Operation.ENABLE_ENDLESS_MODE,
            TeamPermissionPolicy.Operation.RESET_CHAPTER,
            TeamPermissionPolicy.Operation.ADMIN_COMPENSATION
        }) {
            assertEquals(
                    TeamPermissionPolicy.Decision.DENY,
                    TeamPermissionPolicy.decide(
                            operation,
                            member,
                            CAPTAIN_ONLINE,
                            TeamPermissionPolicy.Config.defaults()));
        }
    }

    @Test
    void offlineCaptainPolicyCanKeepHighImpactActionsAvailableOrDenyThem() {
        TeamPermissionPolicy.Requester member =
                new TeamPermissionPolicy.Requester(MEMBER, true, false, 0);
        TeamPermissionPolicy.Config available =
                TeamPermissionPolicy.Config.defaults().withAllowCaptainOnlyOperationsWhenOffline(true);
        TeamPermissionPolicy.Config locked =
                TeamPermissionPolicy.Config.defaults().withAllowCaptainOnlyOperationsWhenOffline(false);

        assertEquals(
                TeamPermissionPolicy.Decision.ALLOW,
                TeamPermissionPolicy.decide(
                        TeamPermissionPolicy.Operation.TRAIN_RECOVERY,
                        member,
                        CAPTAIN_OFFLINE,
                        available));
        assertEquals(
                TeamPermissionPolicy.Decision.DENY,
                TeamPermissionPolicy.decide(
                        TeamPermissionPolicy.Operation.TRAIN_RECOVERY,
                        member,
                        CAPTAIN_OFFLINE,
                        locked));
    }

    @Test
    void consoleSpectatorAndOutsiderAreRejectedBeforeRoleChecks() {
        TeamPermissionPolicy.Operation operation =
                TeamPermissionPolicy.Operation.TRAIN_RECOVERY;
        assertEquals(
                TeamPermissionPolicy.Decision.DENY,
                TeamPermissionPolicy.decide(
                        operation,
                        new TeamPermissionPolicy.Requester(CAPTAIN, false, false, 4),
                        CAPTAIN_ONLINE,
                        TeamPermissionPolicy.Config.defaults()));
        assertEquals(
                TeamPermissionPolicy.Decision.DENY,
                TeamPermissionPolicy.decide(
                        operation,
                        new TeamPermissionPolicy.Requester(CAPTAIN, true, true, 4),
                        CAPTAIN_ONLINE,
                        TeamPermissionPolicy.Config.defaults()));
        assertEquals(
                TeamPermissionPolicy.Decision.DENY,
                TeamPermissionPolicy.decide(
                        operation,
                        new TeamPermissionPolicy.Requester(OUTSIDER, true, false, 4),
                        CAPTAIN_ONLINE,
                        TeamPermissionPolicy.Config.defaults()));
    }

    @Test
    void captainClaimRequiresAnOfflineThresholdAndARealTeamPlayer() {
        assertFalse(TeamPermissionPolicy.canClaimCaptain(
                new TeamPermissionPolicy.Requester(MEMBER, true, false, 0),
                CAPTAIN_OFFLINE,
                100,
                99,
                10));
        assertFalse(TeamPermissionPolicy.canClaimCaptain(
                new TeamPermissionPolicy.Requester(MEMBER, true, false, 0),
                CAPTAIN_OFFLINE,
                100,
                109,
                10));
        assertTrue(TeamPermissionPolicy.canClaimCaptain(
                new TeamPermissionPolicy.Requester(MEMBER, true, false, 0),
                CAPTAIN_OFFLINE,
                100,
                110,
                10));
        assertFalse(TeamPermissionPolicy.canClaimCaptain(
                new TeamPermissionPolicy.Requester(OUTSIDER, true, false, 0),
                CAPTAIN_OFFLINE,
                100,
                110,
                10));
        assertFalse(TeamPermissionPolicy.canClaimCaptain(
                new TeamPermissionPolicy.Requester(MEMBER, false, false, 0),
                CAPTAIN_OFFLINE,
                100,
                110,
                10));
    }
}
