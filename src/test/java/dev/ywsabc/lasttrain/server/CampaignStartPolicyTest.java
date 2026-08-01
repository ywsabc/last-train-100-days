package dev.ywsabc.lasttrain.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CampaignStartPolicyTest {
    @Test
    void startsOnlyAfterEveryRuntimePrerequisiteIsReady() {
        assertTrue(CampaignStartPolicy.shouldAutoStart(true, true, true, true, true));

        for (int missing = 0; missing < 5; missing++) {
            boolean[] readiness = {true, true, true, true, true};
            readiness[missing] = false;
            assertFalse(CampaignStartPolicy.shouldAutoStart(
                    readiness[0],
                    readiness[1],
                    readiness[2],
                    readiness[3],
                    readiness[4]));
        }
    }
}
