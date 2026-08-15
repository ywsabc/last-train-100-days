package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataTeamPermissionTest {
    private static final UUID CAPTAIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Test
    void startingWithAPlayerUuidAssignsTheFirstCaptainAndRegistersThem() {
        CampaignSavedData data = new CampaignSavedData();

        assertTrue(data.start(CAPTAIN));
        assertTrue(data.isTeamMember(CAPTAIN));
        assertTrue(data.isCaptain(CAPTAIN));
        assertEquals(CAPTAIN, data.captainId());
        assertFalse(data.start(MEMBER));
    }

    @Test
    void explicitStarterUuidWinsOverPreStartMembership() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.registerTeamMember(MEMBER));

        assertTrue(data.start(CAPTAIN));
        assertEquals(CAPTAIN, data.captainId());
        assertTrue(data.isTeamMember(MEMBER));
        assertTrue(data.isTeamMember(CAPTAIN));
    }

    @Test
    void captainCanTransferOnlyToAnotherRegisteredMemberAndTimestampIsSaved() {
        CampaignSavedData data = started();
        assertTrue(data.registerTeamMember(MEMBER));

        assertFalse(data.transferCaptain(MEMBER, THIRD, 50));
        assertFalse(data.transferCaptain(CAPTAIN, THIRD, 50));
        assertTrue(data.registerTeamMember(THIRD));
        assertFalse(data.transferCaptain(CAPTAIN, CAPTAIN, 50));
        assertTrue(data.transferCaptain(CAPTAIN, MEMBER, 50));

        assertEquals(MEMBER, data.captainId());
        assertFalse(data.isCaptain(CAPTAIN));
        assertTrue(data.isCaptain(MEMBER));
        assertEquals(50, data.captainTransferTick());

        CampaignSavedData loaded = CampaignSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(MEMBER, loaded.captainId());
        assertEquals(50, loaded.captainTransferTick());
        assertTrue(loaded.isTeamMember(CAPTAIN));
    }

    @Test
    void memberCanClaimAfterInjectedOfflineThresholdAndBecomesCaptain() {
        CampaignSavedData data = started();
        assertTrue(data.registerTeamMember(MEMBER));
        data.observeCaptainOnline(false, 100);

        assertFalse(data.claimCaptain(MEMBER, 10_099));
        assertTrue(data.claimCaptain(MEMBER, 100 + TeamPermissionPolicy.DEFAULT_CAPTAIN_CLAIM_THRESHOLD_TICKS));
        assertEquals(MEMBER, data.captainId());
        assertEquals(100 + TeamPermissionPolicy.DEFAULT_CAPTAIN_CLAIM_THRESHOLD_TICKS,
                data.captainTransferTick());
        assertEquals(-1, data.captainOfflineSinceTick());
        assertFalse(data.claimCaptain(CAPTAIN, 99_999));
    }

    @Test
    void captainTransferResetsOfflineClaimStateAndLoginLogoutObservationIsDurable() {
        CampaignSavedData data = started();
        assertTrue(data.registerTeamMember(MEMBER));
        data.observeCaptainOnline(false, 20);
        assertEquals(20, data.captainOfflineSinceTick());
        assertTrue(data.transferCaptain(CAPTAIN, MEMBER, 30));
        assertEquals(-1, data.captainOfflineSinceTick());
        data.observeCaptainOnline(false, 40);
        assertEquals(40, data.captainOfflineSinceTick());

        CampaignSavedData loaded = CampaignSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(40, loaded.captainOfflineSinceTick());
        assertFalse(loaded.captainOnline());
    }

    @Test
    void votePassesWithSimpleMajorityAndCannotBeStartedTwice() {
        CampaignSavedData data = started();
        assertTrue(data.registerTeamMember(MEMBER));
        assertTrue(data.registerTeamMember(THIRD));

        assertEquals(
                TeamPermissionPolicy.VoteResult.STARTED,
                data.startVote(TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION, CAPTAIN, 100));
        assertEquals(
                TeamPermissionPolicy.VoteResult.ALREADY_PENDING,
                data.startVote(TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION, CAPTAIN, 101));
        assertEquals(
                TeamPermissionPolicy.VoteResult.VOTE_RECORDED,
                data.castVote(MEMBER, true, 110));
        assertEquals(
                TeamPermissionPolicy.VoteResult.PASSED,
                data.castVote(THIRD, true, 111));
        assertTrue(data.pendingVote().isEmpty());
    }

    @Test
    void voteCanBeRejectedAndVoterCannotVoteTwice() {
        CampaignSavedData data = started();
        assertTrue(data.registerTeamMember(MEMBER));
        assertTrue(data.registerTeamMember(THIRD));
        assertEquals(
                TeamPermissionPolicy.VoteResult.STARTED,
                data.startVote(TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION, CAPTAIN, 0));

        assertEquals(
                TeamPermissionPolicy.VoteResult.VOTE_RECORDED,
                data.castVote(MEMBER, false, 1));
        assertEquals(
                TeamPermissionPolicy.VoteResult.ALREADY_VOTED,
                data.castVote(MEMBER, true, 2));
        assertEquals(
                TeamPermissionPolicy.VoteResult.REJECTED,
                data.castVote(THIRD, false, 3));
        assertTrue(data.pendingVote().isEmpty());
    }

    @Test
    void expiredVoteIsSafeAndASeparateVoteMayThenStart() {
        CampaignSavedData data = started();
        assertTrue(data.registerTeamMember(MEMBER));
        TeamPermissionPolicy.Config config = TeamPermissionPolicy.Config.defaults()
                .withVoteDurationTicks(5);
        assertEquals(
                TeamPermissionPolicy.VoteResult.STARTED,
                data.startVote(
                        TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION,
                        CAPTAIN,
                        100,
                        config));
        assertEquals(
                TeamPermissionPolicy.VoteResult.EXPIRED,
                data.expirePendingVote(105));
        assertTrue(data.pendingVote().isEmpty());
        assertEquals(
                TeamPermissionPolicy.VoteResult.STARTED,
                data.startVote(
                        TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION,
                        CAPTAIN,
                        106,
                        config));
    }

    @Test
    void outsiderAndNonCaptainCannotInitiateOrCastTheVote() {
        CampaignSavedData data = started();
        assertTrue(data.registerTeamMember(MEMBER));
        assertEquals(
                TeamPermissionPolicy.VoteResult.NOT_ALLOWED,
                data.startVote(TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION, MEMBER, 0));
        assertEquals(
                TeamPermissionPolicy.VoteResult.STARTED,
                data.startVote(TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION, CAPTAIN, 0));
        assertEquals(
                TeamPermissionPolicy.VoteResult.NOT_ALLOWED,
                data.castVote(OUTSIDER, true, 1));
    }

    @Test
    void oldSavesHaveSafeTeamDefaultsAndPendingVotesAreDiscardedOnReload() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", 7);
        old.putString("campaign_id", UUID.randomUUID().toString());
        old.putString("status", CampaignStatus.NOT_STARTED.name());
        CampaignSavedData loaded = CampaignSavedData.load(old, null);

        assertNull(loaded.captainId());
        assertTrue(loaded.teamMembers().isEmpty());
        assertEquals(-1, loaded.captainOfflineSinceTick());
        assertTrue(loaded.pendingVote().isEmpty());

        CampaignSavedData data = started();
        assertTrue(data.registerTeamMember(MEMBER));
        assertEquals(
                TeamPermissionPolicy.VoteResult.STARTED,
                data.startVote(TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION, CAPTAIN, 10));
        CompoundTag save = data.save(new CompoundTag(), null);
        assertTrue(save.contains("pending_team_vote"));
        CampaignSavedData restarted = CampaignSavedData.load(save, null);
        assertTrue(restarted.pendingVote().isEmpty());
    }

    @Test
    void voteRecordExposesCountsForFeedback() {
        CampaignSavedData data = started();
        assertTrue(data.registerTeamMember(MEMBER));
        assertEquals(
                TeamPermissionPolicy.VoteResult.STARTED,
                data.startVote(TeamPermissionPolicy.Operation.ABANDON_MANDATORY_MISSION, CAPTAIN, 1));
        assertNotNull(data.pendingVote().orElseThrow());
        data.castVote(MEMBER, true, 2);
        assertEquals(1, data.pendingVote().orElseThrow().yesVotes().size());
        assertEquals(0, data.pendingVote().orElseThrow().noVotes().size());
    }

    private static CampaignSavedData started() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start(CAPTAIN));
        return data;
    }
}
