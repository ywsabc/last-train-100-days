package dev.ywsabc.lasttrain.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignIntegrityPolicy;
import dev.ywsabc.lasttrain.campaign.CampaignMode;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.campaign.InfectionPolicy;
import dev.ywsabc.lasttrain.campaign.PursuitPolicy;
import dev.ywsabc.lasttrain.mission.MissionBriefing;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.route.RouteProgressPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TranslationResourcesTest {
    private static final Pattern KEY = Pattern.compile("(?m)^\\s*\"([^\"]+)\"\\s*:");

    @Test
    void englishAndChineseExposeTheSameUniqueKeys() throws IOException {
        assertEquals(readKeys("en_us"), readKeys("zh_cn"));
    }

    @Test
    void everyCentralDomainKeyExistsInBothLanguages() throws IOException {
        Set<String> keys = readKeys("en_us");
        for (CampaignMode value : CampaignMode.values()) {
            assertPresent(keys, TranslationKeys.campaignMode(value));
        }
        for (CampaignStatus value : CampaignStatus.values()) {
            assertPresent(keys, TranslationKeys.campaignStatus(value));
        }
        for (MissionType value : MissionType.values()) {
            assertPresent(keys, TranslationKeys.mission(value));
        }
        for (MissionStage value : MissionStage.values()) {
            assertPresent(keys, TranslationKeys.missionStage(value));
        }
        for (MissionType.MissionPhase value : MissionType.MissionPhase.values()) {
            assertPresent(keys, TranslationKeys.missionPhase(value));
        }
        for (PursuitPolicy.AttentionLevel value : PursuitPolicy.AttentionLevel.values()) {
            assertPresent(keys, TranslationKeys.attention(value));
        }
        for (InfectionPolicy.Stage value : InfectionPolicy.Stage.values()) {
            assertPresent(keys, TranslationKeys.infectionStage(value));
        }
        for (MissionBriefing.Risk value : MissionBriefing.Risk.values()) {
            assertPresent(keys, TranslationKeys.briefingRisk(value));
        }
        for (MissionBriefing.RewardCategory value : MissionBriefing.RewardCategory.values()) {
            assertPresent(keys, TranslationKeys.briefingReward(value));
        }
        for (RouteProgressPolicy.Pace value : RouteProgressPolicy.Pace.values()) {
            assertPresent(keys, TranslationKeys.pace(value));
        }
        for (CampaignIntegrityPolicy.Code value : CampaignIntegrityPolicy.Code.values()) {
            assertPresent(keys, TranslationKeys.integrity(value));
        }
        for (CampaignIntegrityPolicy.Severity value : CampaignIntegrityPolicy.Severity.values()) {
            assertPresent(keys, TranslationKeys.integritySeverity(value));
        }
        assertPresent(keys, TranslationKeys.booleanValue(false));
        assertPresent(keys, TranslationKeys.booleanValue(true));
    }

    private static Set<String> readKeys(String locale) throws IOException {
        String resource = "assets/lasttrain/lang/" + locale + ".json";
        try (InputStream stream = TranslationResourcesTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = KEY.matcher(json);
            Set<String> keys = new LinkedHashSet<>();
            while (matcher.find()) {
                String key = matcher.group(1);
                assertTrue(keys.add(key), "重复语言键：" + locale + "/" + key);
            }
            return Set.copyOf(keys);
        }
    }

    private static void assertPresent(Set<String> keys, String key) {
        assertTrue(keys.contains(key), "缺少语言键：" + key);
    }
}
