package dev.ywsabc.lasttrain.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignIntegrityPolicy;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
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
}
