package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionCommandPolicy.Actor;
import dev.ywsabc.lasttrain.mission.MissionCommandPolicy.TargetCheck;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MissionCommandPolicyTest {
    private static final Actor TEAM_PLAYER =
            new Actor(true, false, true, 0, false);

    @Test
    void consoleAndCommandBlockSourcesAreRejected() {
        assertFalse(MissionCommandPolicy.mayAnswer(new Actor(false, false, true, 4, true)));
        assertFalse(MissionCommandPolicy.mayAnswer(new Actor(false, false, false, 4, true)));
    }

    @Test
    void spectatorsAndOutsidersAreRejected() {
        assertFalse(MissionCommandPolicy.mayAnswer(new Actor(true, true, true, 0, false)));
        assertFalse(MissionCommandPolicy.mayAnswer(new Actor(true, false, false, 0, false)));
    }

    @Test
    void ordinaryTeamMembersMayAnswerButNotSkip() {
        assertTrue(MissionCommandPolicy.mayAnswer(TEAM_PLAYER));
        assertFalse(MissionCommandPolicy.maySkip(TEAM_PLAYER));
    }

    @Test
    void captainOrOperatorLevelTwoMaySkip() {
        assertTrue(MissionCommandPolicy.maySkip(new Actor(true, false, true, 0, true)));
        assertTrue(MissionCommandPolicy.maySkip(new Actor(true, false, true, 2, false)));
        assertTrue(MissionCommandPolicy.maySkip(new Actor(true, false, true, 4, false)));
    }

    @Test
    void operatorWithoutTeamMembershipCannotSkip() {
        assertFalse(MissionCommandPolicy.maySkip(new Actor(true, false, false, 4, false)));
    }

    @Test
    void captainWithoutTeamMembershipCannotSkip() {
        assertFalse(MissionCommandPolicy.maySkip(new Actor(true, false, false, 0, true)));
    }

    @Test
    void targetCheckComparesIdAndRevisionAgainstTheLiveProposal() {
        UUID proposal = UUID.randomUUID();

        assertEquals(TargetCheck.NO_PROPOSAL, MissionCommandPolicy.checkProposalTarget(null, 0, null, null));
        assertEquals(TargetCheck.MATCH, MissionCommandPolicy.checkProposalTarget(proposal, 3, null, null));
        assertEquals(TargetCheck.MATCH, MissionCommandPolicy.checkProposalTarget(proposal, 3, proposal, null));
        assertEquals(TargetCheck.MATCH, MissionCommandPolicy.checkProposalTarget(proposal, 3, proposal, 3));
        assertEquals(TargetCheck.STALE_ID, MissionCommandPolicy.checkProposalTarget(
                proposal, 3, UUID.randomUUID(), null));
        assertEquals(TargetCheck.STALE_REVISION, MissionCommandPolicy.checkProposalTarget(
                proposal, 3, proposal, 2));
    }
}
