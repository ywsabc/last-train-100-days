package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignIntegrityPolicy;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Isolates one server-tick phase so a single policy cannot abort the tick. */
public final class CampaignTickGuard {
    public static final int FAILURE_BACKOFF_THRESHOLD = 5;
    public static final int FAILURE_BACKOFF_CALLS = 20;
    private static final int MAX_DETAIL_LENGTH = 240;
    private static final Map<CampaignSavedData, Map<String, PhaseFailureState>> FAILURE_STATES =
            new WeakHashMap<>();

    private CampaignTickGuard() {
    }

    public static boolean run(CampaignSavedData data, String phase, Runnable action) {
        return call(data, phase, () -> {
            action.run();
            return true;
        }, false);
    }

    /** SAFE_MODE 下禁止执行任何会改变世界的阶段。 */
    public static boolean runWorldWrite(
            CampaignSavedData data,
            String phase,
            Runnable action) {
        return callWorldWrite(data, phase, () -> {
            action.run();
            return true;
        }, false);
    }

    public static <T> T call(
            CampaignSavedData data,
            String phase,
            Supplier<T> action,
            T fallback) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(action, "action");
        if (consumeBackoff(data, phase)) {
            return fallback;
        }
        try {
            T result = action.get();
            clearFailures(data, phase);
            return result;
        } catch (RuntimeException | LinkageError exception) {
            recordFailure(data, phase, exception);
            return fallback;
        }
    }

    public static <T> T callWorldWrite(
            CampaignSavedData data,
            String phase,
            Supplier<T> action,
            T fallback) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(action, "action");
        if (!allowsWorldWrite(data, phase)) {
            return fallback;
        }
        return call(data, phase, action, fallback);
    }

    /** 供事件回调在修改实体或方块前使用的 fail-closed 判定。 */
    public static boolean allowsWorldWrite(CampaignSavedData data, String phase) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(phase, "phase");
        if (data.status() != CampaignStatus.SAFE_MODE) {
            return true;
        }
        recordSafeModeSkip(data, phase);
        return false;
    }

    private static void recordFailure(CampaignSavedData data, String phase, Throwable exception) {
        String message = exception.getMessage();
        String detail = phase + ":" + exception.getClass().getSimpleName();
        if (message != null && !message.isBlank()) {
            detail += ":" + message.replace('\n', ' ').replace('\r', ' ');
        }
        if (detail.length() > MAX_DETAIL_LENGTH) {
            detail = detail.substring(0, MAX_DETAIL_LENGTH);
        }
        boolean firstOccurrence = data.recordIntegrityEvent(
                CampaignIntegrityPolicy.Severity.ERROR,
                CampaignIntegrityPolicy.Code.TICK_EVALUATION_FAILURE,
                detail);
        if (firstOccurrence) {
            LastTrain.LOGGER.error(
                    "Campaign tick phase {} failed and was isolated; later phases will continue",
                    phase,
                    exception);
        }
        int consecutiveFailures = incrementFailures(data, phase);
        if (consecutiveFailures >= FAILURE_BACKOFF_THRESHOLD) {
            recordBackoff(data, phase);
        }
    }

    private static void recordSafeModeSkip(CampaignSavedData data, String phase) {
        String reasons = data.safeModeReasons().stream()
                .map(reason -> reason.serializedName())
                .sorted()
                .collect(Collectors.joining(","));
        if (reasons.isEmpty()) {
            reasons = "unknown";
        }
        String detail = phase + ":" + reasons;
        boolean firstOccurrence = data.recordIntegrityEvent(
                CampaignIntegrityPolicy.Severity.WARNING,
                CampaignIntegrityPolicy.Code.SAFE_MODE_WORLD_WRITE_SKIPPED,
                detail);
        if (firstOccurrence) {
            LastTrain.LOGGER.warn(
                    "Campaign world-write phase {} was skipped in SAFE_MODE ({})",
                    phase,
                    reasons);
        }
    }

    private static int incrementFailures(CampaignSavedData data, String phase) {
        synchronized (FAILURE_STATES) {
            PhaseFailureState state = FAILURE_STATES
                    .computeIfAbsent(data, ignored -> new HashMap<>())
                    .computeIfAbsent(phase, ignored -> new PhaseFailureState());
            if (state.consecutiveFailures < FAILURE_BACKOFF_THRESHOLD) {
                state.consecutiveFailures++;
            }
            if (state.consecutiveFailures >= FAILURE_BACKOFF_THRESHOLD) {
                state.remainingBackoffCalls = FAILURE_BACKOFF_CALLS;
            }
            return state.consecutiveFailures;
        }
    }

    private static boolean consumeBackoff(CampaignSavedData data, String phase) {
        synchronized (FAILURE_STATES) {
            Map<String, PhaseFailureState> phases = FAILURE_STATES.get(data);
            if (phases == null) {
                return false;
            }
            PhaseFailureState state = phases.get(phase);
            if (state == null || state.remainingBackoffCalls <= 0) {
                return false;
            }
            state.remainingBackoffCalls--;
            return true;
        }
    }

    private static void clearFailures(CampaignSavedData data, String phase) {
        synchronized (FAILURE_STATES) {
            Map<String, PhaseFailureState> phases = FAILURE_STATES.get(data);
            if (phases == null) {
                return;
            }
            phases.remove(phase);
            if (phases.isEmpty()) {
                FAILURE_STATES.remove(data);
            }
        }
    }

    private static void recordBackoff(CampaignSavedData data, String phase) {
        String detail = phase
                + ":after_"
                + FAILURE_BACKOFF_THRESHOLD
                + "_failures:skip_"
                + FAILURE_BACKOFF_CALLS
                + "_calls";
        boolean firstOccurrence = data.recordIntegrityEvent(
                CampaignIntegrityPolicy.Severity.WARNING,
                CampaignIntegrityPolicy.Code.TICK_EVALUATION_BACKOFF,
                detail);
        if (firstOccurrence) {
            LastTrain.LOGGER.warn(
                    "Campaign tick phase {} entered a {}-call backoff after {} consecutive failures",
                    phase,
                    FAILURE_BACKOFF_CALLS,
                    FAILURE_BACKOFF_THRESHOLD);
        }
    }

    private static final class PhaseFailureState {
        private int consecutiveFailures;
        private int remainingBackoffCalls;
    }
}
