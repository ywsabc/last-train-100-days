package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignIntegrityPolicy;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import java.util.Objects;
import java.util.function.Supplier;

/** Isolates one server-tick phase so a single policy cannot abort the tick. */
public final class CampaignTickGuard {
    private static final int MAX_DETAIL_LENGTH = 240;

    private CampaignTickGuard() {
    }

    public static boolean run(CampaignSavedData data, String phase, Runnable action) {
        return call(data, phase, () -> {
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
        try {
            return action.get();
        } catch (RuntimeException | LinkageError exception) {
            recordFailure(data, phase, exception);
            return fallback;
        }
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
    }
}
