package dev.ywsabc.lasttrain.campaign;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side permission and voting rules for high-impact team actions.
 *
 * <p>This class deliberately contains no Minecraft or wall-clock dependency.
 * The caller supplies the current logical tick, player-source facts and the
 * current team snapshot. That keeps command validation authoritative while
 * making the captain hand-off and vote deadlines deterministic in tests.</p>
 */
public final class TeamPermissionPolicy {
    public static final int ADMIN_PERMISSION_LEVEL = 2;
    /** Twenty ticks per second for ten real-time minutes. */
    public static final long DEFAULT_CAPTAIN_CLAIM_THRESHOLD_TICKS = 12_000L;
    /** Five real-time minutes is long enough for a reconnect, but finite. */
    public static final long DEFAULT_VOTE_DURATION_TICKS = 6_000L;

    private TeamPermissionPolicy() {
    }

    /** High-impact operations shared by commands and future interaction APIs. */
    public enum Operation {
        ABANDON_MANDATORY_MISSION("abandon_mandatory_mission", false),
        TRAIN_RECOVERY("train_recovery", false),
        DISMANTLE_CRITICAL_CONTROL("dismantle_critical_control", false),
        ENABLE_ENDLESS_MODE("enable_endless_mode", false),
        RESET_CHAPTER("reset_chapter", true),
        ADMIN_COMPENSATION("admin_compensation", true);

        private final String serializedName;
        private final boolean adminOnly;

        Operation(String serializedName, boolean adminOnly) {
            this.serializedName = serializedName;
            this.adminOnly = adminOnly;
        }

        public String serializedName() {
            return serializedName;
        }

        public boolean adminOnly() {
            return adminOnly;
        }

        public static Operation parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Operation operation : values()) {
                if (operation.serializedName.equals(normalized)
                        || operation.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return operation;
                }
            }
            return null;
        }
    }

    public enum Decision {
        ALLOW,
        DENY,
        NEED_VOTE
    }

    /**
     * Small integration seam for future block/menu/mode handlers. A handler
     * can retain this interface without importing command code; P6 endless
     * mode and the train-control protection layer can use the same gate.
     */
    @FunctionalInterface
    public interface Gate {
        Decision decide(Operation operation, Requester requester, TeamState team);
    }

    /** Results returned by the durable vote state machine. */
    public enum VoteResult {
        STARTED,
        ALREADY_PENDING,
        VOTE_RECORDED,
        PENDING,
        PASSED,
        REJECTED,
        EXPIRED,
        NO_ACTIVE_VOTE,
        ALREADY_VOTED,
        NOT_ALLOWED,
        INVALID_OPERATION
    }

    /** Facts about the command requester; no live player object is retained. */
    public record Requester(
            UUID playerId,
            boolean playerSource,
            boolean spectator,
            int permissionLevel) {
        public Requester {
            permissionLevel = Math.max(0, permissionLevel);
        }

        public boolean isAdmin() {
            return permissionLevel >= ADMIN_PERMISSION_LEVEL;
        }
    }

    /** The current authoritative team facts used for one decision. */
    public record TeamState(UUID captainId, Set<UUID> members, boolean captainOnline) {
        public TeamState {
            members = members == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(members));
        }

        public boolean hasCaptain() {
            return captainId != null && members.contains(captainId);
        }
    }

    /**
     * Configuration hooks for a server's desired risk/availability tradeoff.
     * The defaults keep destructive chapter/admin operations operator-only,
     * permit a captain-offline team to use a configured vote path, and expire
     * votes after five real-time minutes at 20 TPS.
     */
    public record Config(
            boolean allowCaptainOnlyOperationsWhenOffline,
            boolean allowMemberVoteWhenCaptainOffline,
            long captainClaimThresholdTicks,
            long voteDurationTicks,
            Set<Operation> voteOperations) {
        public Config {
            captainClaimThresholdTicks = Math.max(1L, captainClaimThresholdTicks);
            voteDurationTicks = Math.max(1L, voteDurationTicks);
            voteOperations = voteOperations == null || voteOperations.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(voteOperations));
        }

        public Config(
                boolean allowCaptainOnlyOperationsWhenOffline,
                boolean allowMemberVoteWhenCaptainOffline,
                long captainClaimThresholdTicks,
                long voteDurationTicks) {
            this(
                    allowCaptainOnlyOperationsWhenOffline,
                    allowMemberVoteWhenCaptainOffline,
                    captainClaimThresholdTicks,
                    voteDurationTicks,
                    Set.of(Operation.ABANDON_MANDATORY_MISSION));
        }

        public static Config defaults() {
            return new Config(
                    false,
                    true,
                    DEFAULT_CAPTAIN_CLAIM_THRESHOLD_TICKS,
                    DEFAULT_VOTE_DURATION_TICKS,
                    Set.of(Operation.ABANDON_MANDATORY_MISSION));
        }

        public Config withAllowCaptainOnlyOperationsWhenOffline(boolean allowed) {
            return new Config(
                    allowed,
                    allowMemberVoteWhenCaptainOffline,
                    captainClaimThresholdTicks,
                    voteDurationTicks,
                    voteOperations);
        }

        public Config withAllowMemberVoteWhenCaptainOffline(boolean allowed) {
            return new Config(
                    allowCaptainOnlyOperationsWhenOffline,
                    allowed,
                    captainClaimThresholdTicks,
                    voteDurationTicks,
                    voteOperations);
        }

        public Config withCaptainClaimThresholdTicks(long threshold) {
            return new Config(
                    allowCaptainOnlyOperationsWhenOffline,
                    allowMemberVoteWhenCaptainOffline,
                    threshold,
                    voteDurationTicks,
                    voteOperations);
        }

        public Config withVoteDurationTicks(long duration) {
            return new Config(
                    allowCaptainOnlyOperationsWhenOffline,
                    allowMemberVoteWhenCaptainOffline,
                    captainClaimThresholdTicks,
                    duration,
                    voteOperations);
        }

        public Config withVoteOperations(Set<Operation> operations) {
            return new Config(
                    allowCaptainOnlyOperationsWhenOffline,
                    allowMemberVoteWhenCaptainOffline,
                    captainClaimThresholdTicks,
                    voteDurationTicks,
                    operations);
        }

        public boolean allowsVote(Operation operation) {
            return operation != null && voteOperations.contains(operation);
        }
    }

    /** Durable, UUID-only vote state. */
    public record Vote(
            Operation operation,
            UUID initiatedBy,
            long startedAtTick,
            long expiresAtTick,
            Set<UUID> yesVotes,
            Set<UUID> noVotes) {
        public Vote {
            yesVotes = immutableUuidSet(yesVotes);
            noVotes = immutableUuidSet(noVotes);
        }

        public boolean expiredAt(long currentTick) {
            return currentTick >= expiresAtTick;
        }

        public boolean hasVoted(UUID playerId) {
            return playerId != null && (yesVotes.contains(playerId) || noVotes.contains(playerId));
        }

        public Vote cast(UUID playerId, boolean yes) {
            Set<UUID> nextYes = new LinkedHashSet<>(yesVotes);
            Set<UUID> nextNo = new LinkedHashSet<>(noVotes);
            if (yes) {
                nextYes.add(playerId);
            } else {
                nextNo.add(playerId);
            }
            return new Vote(operation, initiatedBy, startedAtTick, expiresAtTick, nextYes, nextNo);
        }

        private static Set<UUID> immutableUuidSet(Set<UUID> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            LinkedHashSet<UUID> copy = new LinkedHashSet<>();
            for (UUID value : values) {
                if (value != null) {
                    copy.add(value);
                }
            }
            return Collections.unmodifiableSet(copy);
        }
    }

    public static Decision decide(
            Operation operation,
            Requester requester,
            TeamState team,
            Config config) {
        if (operation == null || requester == null || team == null || config == null) {
            return Decision.DENY;
        }
        if (!requester.playerSource()
                || requester.spectator()
                || requester.playerId() == null
                || !team.members().contains(requester.playerId())) {
            return Decision.DENY;
        }

        if (requester.isAdmin()) {
            return Decision.ALLOW;
        }
        if (operation.adminOnly() || !team.hasCaptain()) {
            return Decision.DENY;
        }

        boolean captain = requester.playerId().equals(team.captainId());
        if (captain) {
            return Decision.ALLOW;
        }

        if (!team.captainOnline()
                && config.allowCaptainOnlyOperationsWhenOffline()
                && operation != Operation.ABANDON_MANDATORY_MISSION) {
            return Decision.ALLOW;
        }
        if (config.allowsVote(operation)
                && (team.captainOnline() || config.allowMemberVoteWhenCaptainOffline())) {
            return Decision.NEED_VOTE;
        }
        return Decision.DENY;
    }

    /** Alias named for call sites that describe the check as an evaluation. */
    public static Decision evaluate(
            Operation operation,
            Requester requester,
            TeamState team,
            Config config) {
        return decide(operation, requester, team, config);
    }

    public static Gate gate(Config config) {
        Config safeConfig = config == null ? Config.defaults() : config;
        return (operation, requester, team) -> decide(operation, requester, team, safeConfig);
    }

    /** Whether this requester is allowed to open a vote for the operation. */
    public static boolean canInitiateVote(
            Operation operation,
            Requester requester,
            TeamState team,
            Config config) {
        if (operation == null
                || requester == null
                || team == null
                || config == null
                || !config.allowsVote(operation)
                || operation.adminOnly()
                || !requester.playerSource()
                || requester.spectator()
                || requester.playerId() == null
                || !team.members().contains(requester.playerId())) {
            return false;
        }
        if (requester.isAdmin() || requester.playerId().equals(team.captainId())) {
            return true;
        }
        return !team.captainOnline() && config.allowMemberVoteWhenCaptainOffline();
    }

    public static boolean canClaimCaptain(
            Requester requester,
            TeamState team,
            long captainOfflineSinceTick,
            long currentTick,
            long thresholdTicks) {
        if (requester == null
                || team == null
                || requester.playerId() == null
                || !requester.playerSource()
                || requester.spectator()
                || !team.members().contains(requester.playerId())
                || !team.hasCaptain()
                || team.captainOnline()
                || requester.playerId().equals(team.captainId())
                || captainOfflineSinceTick < 0
                || currentTick < captainOfflineSinceTick) {
            return false;
        }
        return currentTick - captainOfflineSinceTick >= Math.max(1L, thresholdTicks);
    }

    public static boolean votePassed(Vote vote, Set<UUID> teamMembers) {
        if (vote == null || teamMembers == null || teamMembers.isEmpty()) {
            return false;
        }
        int eligible = countEligible(teamMembers);
        return countVotesInTeam(vote.yesVotes(), teamMembers) * 2 > eligible;
    }

    public static boolean voteRejected(Vote vote, Set<UUID> teamMembers) {
        if (vote == null || teamMembers == null || teamMembers.isEmpty()) {
            return true;
        }
        int eligible = countEligible(teamMembers);
        return countVotesInTeam(vote.noVotes(), teamMembers) * 2 >= eligible;
    }

    private static int countEligible(Set<UUID> teamMembers) {
        int count = 0;
        for (UUID member : teamMembers) {
            if (member != null) {
                count++;
            }
        }
        return count;
    }

    private static int countVotesInTeam(Set<UUID> voters, Set<UUID> teamMembers) {
        int count = 0;
        for (UUID voter : voters) {
            if (teamMembers.contains(voter)) {
                count++;
            }
        }
        return count;
    }
}
