package dev.ywsabc.lasttrain.mission;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * 任务实体注册表与世界实体之间的窄接口。
 *
 * <p>UUID 集合是数量上限与清理的权威来源；实体是否正好加载只影响本 tick
 * 能否定位、传送或回收，绝不能让导演把未加载或已经离场的实体误判为缺额。</p>
 */
public interface MissionEntityContainer<E> {
    /** 所有进行中任务与待清理快照合计的真实硬上限。 */
    int MAX_REGISTERED_ENTITIES = 48;

    /** 登记实体；达到全局硬上限时返回 false。重复登记同一任务 UUID 是幂等的。 */
    boolean register(UUID missionId, E entity);

    /** 返回某任务的完整 UUID 白名单，包括当前未加载的实体。 */
    Set<UUID> registeredIds(UUID missionId);

    /** 只按 UUID 直接定位；实现不得退化为全维度实体扫描。 */
    Optional<E> find(UUID missionId, UUID entityId);

    /** 把离开管理半径的活动实体传送回任务现场，保留原 UUID 与占用。 */
    boolean recover(UUID missionId, UUID entityId, BlockPos site);

    /** 回收实体并在成功后移除 UUID；未加载时保留索引，等待其再次加载。 */
    boolean recycle(UUID missionId, UUID entityId);

    /** 仅移除已确认死亡或已经由其他路径销毁的 UUID。 */
    boolean unregister(UUID missionId, UUID entityId);

    /** 全部任务（含待清理快照）的 UUID 占用。 */
    int totalCount();

    default int count(UUID missionId) {
        return registeredIds(missionId).size();
    }

    default int remainingCapacity() {
        return Math.max(0, MAX_REGISTERED_ENTITIES - totalCount());
    }
}
