package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class MissionEntityContainerTest {
    private static final BlockPos SITE = new BlockPos(10, 64, 20);

    @Test
    void offsiteEntityIsRecoveredWithoutReplacementSpawn() {
        FakeMissionEntityContainer entities = new FakeMissionEntityContainer();
        UUID missionId = UUID.randomUUID();
        FakeEntity offsite = entities.add(
                missionId,
                ZombieBlockadePolicy.MANAGEMENT_RADIUS
                        * ZombieBlockadePolicy.MANAGEMENT_RADIUS + 1.0D);

        MissionWorldDirector.IndexedReconciliation result = reconcile(
                entities,
                missionId,
                1,
                0);

        assertEquals(1, result.recovered());
        assertEquals(0, result.toSpawn());
        assertEquals(1, entities.count(missionId));
        assertEquals(List.of(offsite.id), entities.recovered);
    }

    @Test
    void unloadedRegisteredEntitySuppressesReplacementSpawn() {
        FakeMissionEntityContainer entities = new FakeMissionEntityContainer();
        UUID missionId = UUID.randomUUID();
        entities.addUnloaded(missionId, UUID.randomUUID());

        MissionWorldDirector.IndexedReconciliation result = reconcile(
                entities,
                missionId,
                1,
                0);

        assertEquals(0, result.toSpawn());
        assertEquals(1, result.indexedAfterRecycle());
        assertEquals(1, entities.findCalls);
    }

    @Test
    void globalMissionEntityHardLimitSuppressesEveryAdditionalSpawn() {
        FakeMissionEntityContainer entities = new FakeMissionEntityContainer();
        UUID firstMission = UUID.randomUUID();
        UUID secondMission = UUID.randomUUID();
        for (int index = 0; index < 40; index++) {
            entities.add(firstMission, 4.0D);
        }
        for (int index = 0; index < 8; index++) {
            entities.add(secondMission, 4.0D);
        }

        MissionWorldDirector.IndexedReconciliation result = reconcile(
                entities,
                secondMission,
                20,
                0);

        assertEquals(MissionEntityContainer.MAX_REGISTERED_ENTITIES, entities.totalCount());
        assertEquals(0, result.toSpawn());
        assertTrue(result.globalCapSuppressedSpawn());
        assertFalse(entities.register(secondMission, new FakeEntity(UUID.randomUUID(), 4.0D, 0)));
    }

    @Test
    void cleanupRecyclesEveryIndexedEntityAcrossAnyRadius() {
        FakeMissionEntityContainer entities = new FakeMissionEntityContainer();
        UUID missionId = UUID.randomUUID();
        FakeEntity nearby = entities.add(missionId, 1.0D);
        FakeEntity farAway = entities.add(missionId, 1_000_000.0D);

        assertTrue(MissionWorldDirector.recycleMissionEntities(entities, missionId));

        assertEquals(0, entities.count(missionId));
        assertEquals(Set.of(nearby.id, farAway.id), Set.copyOf(entities.recycled));
    }

    @Test
    void survivorLookupUsesOnlyRegisteredUuids() {
        FakeMissionEntityContainer entities = new FakeMissionEntityContainer();
        UUID missionId = UUID.randomUUID();
        entities.addUnloaded(missionId, UUID.randomUUID());
        FakeEntity survivor = entities.add(missionId, 9.0D);

        Optional<FakeEntity> found = OptionalMissionDirector.findRegisteredEntity(
                entities,
                missionId,
                entity -> entity == survivor);

        assertEquals(Optional.of(survivor), found);
        assertEquals(2, entities.findCalls);
        assertEquals(Set.of(survivor.id), Set.copyOf(entities.foundLoadedIds));
    }

    private static MissionWorldDirector.IndexedReconciliation reconcile(
            FakeMissionEntityContainer entities,
            UUID missionId,
            int target,
            int progress) {
        return MissionWorldDirector.reconcileIndexedEntities(
                entities,
                missionId,
                target,
                progress,
                SITE,
                entity -> true,
                entity -> entity.distanceSquared,
                entity -> entity.ageTicks,
                entity -> entity.id);
    }

    private static final class FakeMissionEntityContainer
            implements MissionEntityContainer<FakeEntity> {
        private final Map<UUID, LinkedHashMap<UUID, FakeEntity>> byMission =
                new LinkedHashMap<>();
        private final List<UUID> recovered = new ArrayList<>();
        private final List<UUID> recycled = new ArrayList<>();
        private final List<UUID> foundLoadedIds = new ArrayList<>();
        private int findCalls;

        FakeEntity add(UUID missionId, double distanceSquared) {
            FakeEntity entity = new FakeEntity(UUID.randomUUID(), distanceSquared, 20);
            assertTrue(register(missionId, entity));
            return entity;
        }

        void addUnloaded(UUID missionId, UUID entityId) {
            byMission.computeIfAbsent(missionId, ignored -> new LinkedHashMap<>())
                    .put(entityId, null);
        }

        @Override
        public boolean register(UUID missionId, FakeEntity entity) {
            LinkedHashMap<UUID, FakeEntity> mission =
                    byMission.computeIfAbsent(missionId, ignored -> new LinkedHashMap<>());
            if (mission.containsKey(entity.id)) {
                return true;
            }
            if (totalCount() >= MAX_REGISTERED_ENTITIES) {
                return false;
            }
            mission.put(entity.id, entity);
            return true;
        }

        @Override
        public Set<UUID> registeredIds(UUID missionId) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(
                    byMission.getOrDefault(missionId, new LinkedHashMap<>()).keySet()));
        }

        @Override
        public Optional<FakeEntity> find(UUID missionId, UUID entityId) {
            findCalls++;
            FakeEntity entity = byMission
                    .getOrDefault(missionId, new LinkedHashMap<>())
                    .get(entityId);
            if (entity != null) {
                foundLoadedIds.add(entityId);
            }
            return Optional.ofNullable(entity);
        }

        @Override
        public boolean recover(UUID missionId, UUID entityId, BlockPos site) {
            FakeEntity entity = byMission
                    .getOrDefault(missionId, new LinkedHashMap<>())
                    .get(entityId);
            if (entity == null) {
                return false;
            }
            entity.distanceSquared = 0.0D;
            recovered.add(entityId);
            return true;
        }

        @Override
        public boolean recycle(UUID missionId, UUID entityId) {
            LinkedHashMap<UUID, FakeEntity> mission = byMission.get(missionId);
            if (mission == null || mission.get(entityId) == null) {
                return false;
            }
            mission.remove(entityId);
            recycled.add(entityId);
            return true;
        }

        @Override
        public boolean unregister(UUID missionId, UUID entityId) {
            LinkedHashMap<UUID, FakeEntity> mission = byMission.get(missionId);
            return mission != null && mission.remove(entityId) != null;
        }

        @Override
        public int totalCount() {
            LinkedHashSet<UUID> ids = new LinkedHashSet<>();
            byMission.values().forEach(mission -> ids.addAll(mission.keySet()));
            return ids.size();
        }
    }

    private static final class FakeEntity {
        private final UUID id;
        private double distanceSquared;
        private final int ageTicks;

        private FakeEntity(UUID id, double distanceSquared, int ageTicks) {
            this.id = id;
            this.distanceSquared = distanceSquared;
            this.ageTicks = ageTicks;
        }
    }
}
