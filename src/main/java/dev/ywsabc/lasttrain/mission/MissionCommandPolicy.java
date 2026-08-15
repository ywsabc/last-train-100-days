package dev.ywsabc.lasttrain.mission;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure permission and target-validation policy for optional mission commands.
 *
 * <p>All four commands (status/accept/reject/skip) require a real player
 * command source — never a command block or the console — who is not a
 * spectator and is a registered team member. Skip additionally requires
 * operator level 2 or the team captain. Target validation re-compares the
 * supplied task id and revision against the live proposal so stale UI
 * answers or concurrent commands cannot affect the wrong task.</p>
 */
public final class MissionCommandPolicy {
    public static final int SKIP_PERMISSION_LEVEL = 2;

    private MissionCommandPolicy() {
    }

    /** Command actor facts, decoupled from the command stack for unit tests. */
    public record Actor(
            boolean playerSource,
            boolean spectator,
            boolean teamMember,
            int permissionLevel,
            boolean captain) {
        public Actor {
            permissionLevel = Math.max(0, permissionLevel);
        }
    }

    public static boolean mayAnswer(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        return actor.playerSource() && !actor.spectator() && actor.teamMember();
    }

    public static boolean maySkip(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        return mayAnswer(actor)
                && (actor.permissionLevel() >= SKIP_PERMISSION_LEVEL || actor.captain());
    }

    public enum TargetCheck {
        MATCH,
        NO_PROPOSAL,
        STALE_ID,
        STALE_REVISION
    }

    /**
     * Compares a command's optional task id / revision against the current
     * proposal. A null supplied value means "the only current proposal".
     */
    public static TargetCheck checkProposalTarget(
            UUID proposalId,
            int proposalRevision,
            UUID givenId,
            Integer givenRevision) {
        if (proposalId == null) {
            return TargetCheck.NO_PROPOSAL;
        }
        if (givenId != null && !givenId.equals(proposalId)) {
            return TargetCheck.STALE_ID;
        }
        if (givenRevision != null && givenRevision != proposalRevision) {
            return TargetCheck.STALE_REVISION;
        }
        return TargetCheck.MATCH;
    }
}
