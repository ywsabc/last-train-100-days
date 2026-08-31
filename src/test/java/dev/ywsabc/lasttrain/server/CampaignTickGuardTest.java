package dev.ywsabc.lasttrain.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignIntegrityPolicy;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CampaignTickGuardTest {
    @Test
    void oneFailingPolicyIsRecordedAndLaterPoliciesStillRun() {
        CampaignSavedData data = new CampaignSavedData();
        AtomicInteger completed = new AtomicInteger();

        assertFalse(CampaignTickGuard.run(
                data,
                "test.broken_policy",
                () -> {
                    throw new IllegalStateException("corrupt input");
                }));
        assertTrue(CampaignTickGuard.run(
                data,
                "test.next_policy",
                completed::incrementAndGet));

        assertEquals(1, completed.get());
        assertTrue(CampaignIntegrityPolicy.audit(data).issues().stream()
                .anyMatch(issue -> issue.code()
                                == CampaignIntegrityPolicy.Code.TICK_EVALUATION_FAILURE
                        && issue.detail().startsWith("test.broken_policy:")));
    }

    @Test
    void callReturnsItsSafeFallbackAfterEvaluationFailure() {
        CampaignSavedData data = new CampaignSavedData();

        int result = CampaignTickGuard.call(
                data,
                "test.value_policy",
                () -> {
                    throw new IllegalArgumentException("bad enum");
                },
                17);

        assertEquals(17, result);
        assertEquals(1, data.integrityEvents().size());
    }

    @Test
    void safeModeSkipsMissionDirectorAndCleanupWorldWrites() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        data.observeTrain(false, true, false, false, false, 1);
        assertEquals(CampaignStatus.SAFE_MODE, data.status());
        AtomicInteger worldWrites = new AtomicInteger();

        assertFalse(CampaignTickGuard.runWorldWrite(
                data,
                "campaign.mission_director",
                worldWrites::incrementAndGet));
        assertFalse(CampaignTickGuard.runWorldWrite(
                data,
                "mission.optional_cleanup",
                worldWrites::incrementAndGet));

        assertEquals(0, worldWrites.get());
        assertEquals(
                2,
                data.integrityEvents().stream()
                        .filter(issue -> issue.code()
                                == CampaignIntegrityPolicy.Code.SAFE_MODE_WORLD_WRITE_SKIPPED)
                        .count());

        // 只有载具后端恢复、其拥有的 SAFE_MODE 原因被解除后才重新开放世界写入。
        data.observeTrain(true, true, true, true, false, 1);
        assertEquals(CampaignStatus.RUNNING, data.status());
        assertTrue(CampaignTickGuard.runWorldWrite(
                data,
                "campaign.mission_director",
                worldWrites::incrementAndGet));
        assertEquals(1, worldWrites.get());
    }

    @Test
    void repeatedPhaseFailuresBackOffAndRetryAfterTheWindow() {
        CampaignSavedData data = new CampaignSavedData();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean broken = new AtomicBoolean(true);

        for (int failure = 0;
                failure < CampaignTickGuard.FAILURE_BACKOFF_THRESHOLD;
                failure++) {
            assertFalse(CampaignTickGuard.run(data, "mission.reward_outbox", () -> {
                attempts.incrementAndGet();
                if (broken.get()) {
                    throw new IllegalStateException("broken receipt");
                }
            }));
        }
        broken.set(false);

        for (int skipped = 0;
                skipped < CampaignTickGuard.FAILURE_BACKOFF_CALLS;
                skipped++) {
            assertFalse(CampaignTickGuard.run(
                    data,
                    "mission.reward_outbox",
                    attempts::incrementAndGet));
        }
        assertEquals(CampaignTickGuard.FAILURE_BACKOFF_THRESHOLD, attempts.get());
        assertTrue(data.integrityEvents().stream()
                .anyMatch(issue -> issue.code()
                        == CampaignIntegrityPolicy.Code.TICK_EVALUATION_BACKOFF));

        assertTrue(CampaignTickGuard.run(
                data,
                "mission.reward_outbox",
                attempts::incrementAndGet));
        assertEquals(CampaignTickGuard.FAILURE_BACKOFF_THRESHOLD + 1, attempts.get());
    }
}
