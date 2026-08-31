package dev.ywsabc.lasttrain.mission;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** 使用 CampaignSavedData UUID 索引的服务器世界适配器。 */
final class ServerMissionEntityContainer implements MissionEntityContainer<Entity> {
    private final ServerLevel homeLevel;
    private final CampaignSavedData data;

    ServerMissionEntityContainer(ServerLevel homeLevel, CampaignSavedData data) {
        this.homeLevel = homeLevel;
        this.data = data;
    }

    @Override
    public boolean register(UUID missionId, Entity entity) {
        return entity != null && data.registerMissionEntity(missionId, entity.getUUID());
    }

    @Override
    public Set<UUID> registeredIds(UUID missionId) {
        return data.missionEntityIds(missionId);
    }

    @Override
    public Optional<Entity> find(UUID missionId, UUID entityId) {
        if (!registeredIds(missionId).contains(entityId)) {
            return Optional.empty();
        }
        // getEntity(UUID) 走每个已加载维度的 UUID 索引，不遍历实体集合。
        for (ServerLevel level : homeLevel.getServer().getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null) {
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean recover(UUID missionId, UUID entityId, BlockPos site) {
        Optional<Entity> found = find(missionId, entityId);
        if (found.isEmpty()) {
            return false;
        }
        Entity entity = found.orElseThrow();
        if (entity.level() != homeLevel) {
            // 任务实体不应跨维度活动；在能够安全送回主世界前保留 UUID 占用，
            // 绝不能因为当前无法传送就生成替代实体。
            return false;
        }
        entity.moveTo(
                site.getX() + 0.5D,
                site.getY(),
                site.getZ() + 0.5D,
                entity.getYRot(),
                entity.getXRot());
        return true;
    }

    @Override
    public boolean recycle(UUID missionId, UUID entityId) {
        Optional<Entity> found = find(missionId, entityId);
        if (found.isEmpty()) {
            return false;
        }
        found.orElseThrow().discard();
        // EntityLeaveLevelEvent 可能已同步移除索引；这里按最终状态判定幂等成功。
        unregister(missionId, entityId);
        return !registeredIds(missionId).contains(entityId);
    }

    @Override
    public boolean unregister(UUID missionId, UUID entityId) {
        return data.unregisterMissionEntity(missionId, entityId);
    }

    @Override
    public int totalCount() {
        return data.missionEntityCount();
    }
}
