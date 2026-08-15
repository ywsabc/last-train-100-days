# 末班列车：百日惊变 — 技术架构

> 状态：设计草案  
> 目标平台：Minecraft Java Edition 1.21.1、NeoForge、Create 6.x  
> 部署形态：单人世界、单人世界开放局域网、独立专用服务器  
> 核心模组 ID：`lasttrain`

除明确写为“当前原型/当前实现”的段落外，本文描述的是目标架构与验收契约，
不是仓库现状。第 20 节单独列出当前已落地切片；接口、服务、网络协议、配置、
安全模式和无限模式等设计项在实现前不得视为已有功能。

## 1. 目标与架构原则

本项目的核心不是“在普通生存中加入一辆火车”，而是一套由服务器推进的合作战役：

1. 世界第一次启动时可靠地创建默认站点、默认列车和初始物资。
2. 队伍沿一条可持续向前扩展的主线行驶。
3. 路线前方按确定性规则生成车站、城市和事件区段。
4. 断轨、封锁门、恢复供电、搜救等任务会改变真实世界状态。
5. 单人、局域网和专服使用完全相同的战役规则与存档格式。
6. 物理火车实现可以替换，任务、路线与百日进度不能绑定某个实验模组的内部对象。

实现时遵守以下原则：

- **服务器权威**：战役时间、路线、任务、列车身份、奖励和世界修改只由逻辑服务器决定。
- **确定性优先**：路线与任务由战役种子和区段编号决定；服务器重启不能“刷新”结果。
- **幂等优先**：初始化、任务副作用、奖励发放和故障恢复均可安全重试。
- **适配器隔离**：核心领域代码不直接引用 TongDa Railway、Loconautics、SimuRail 或其他物理后端的类。
- **存档优先于表现**：列车实体或物理对象是可重建的表现，逻辑列车 ID 和战役状态才是事实来源。
- **有限窗口实现“无限”**：只规划和加载列车附近的路线窗口，不强加载已走过的整条铁路。
- **可诊断失败**：实验物理后端不可用时暂停载具初始化并保留现场；正式宣称
  “物理化火车”前必须通过物理后端验收，不能伪装成 Create 原生列车降级。

“无限铁路”是玩法称呼，而不是突破游戏数值边界。当前原型另设
400,000×64，约 2,560 万格的路线安全上限，并且尚无通关后无限模式。

## 2. 运行模型：三种部署共用一套服务器核心

Minecraft 单人世界本身也运行逻辑服务器。三种玩法只在服务器宿主方式上不同：

| 形态 | 权威端 | 客户端数量 | 特别处理 |
| --- | --- | ---: | --- |
| 单人 | Integrated Server | 1 | 游戏暂停时战役也暂停，不用真实时间补算 |
| 开放局域网 | Integrated Server | 1–N | 房主仍是服务器；加入者不得拥有额外权威 |
| Dedicated Server | Dedicated Server | 0–N | 当前百日时钟和 The Hordes 暂停；其他导演与持久票据见第 12 节 |

核心代码只能通过 `MinecraftServer`、`ServerLevel`、服务器事件和服务器线程访问世界，不用 `Minecraft.getInstance()`。任何只在客户端存在的 HUD、音效和渲染代码必须放在独立的 `client` 包并由 Dist 限制加载，确保专服不发生类加载崩溃。

建议逻辑分层如下：

```text
客户端 HUD / 提示 / 地图标记
           │ 语义化网络消息
           ▼
┌──────────────────────────────────────────────┐
│ lasttrain 核心服务（服务器权威）              │
│ Campaign / Route / Mission / Scaling / Rescue│
├──────────────────────────────────────────────┤
│ 稳定领域模型与 SavedData                     │
├──────────────┬──────────────┬────────────────┤
│ RouteBackend │ TrackAdapter │ VehicleBackend │
├──────────────┼──────────────┼────────────────┤
│ TongDa/自有  │ Create 轨道  │ Create/物理模组 │
└──────────────┴──────────────┴────────────────┘
           │
           ▼
区块、方块、实体、Create 图、第三方模组对象
```

初期可以发布为一个 jar，但源码包必须保持以上边界。物理后端如果需要不稳定的编译依赖，优先拆成单独兼容 jar，防止可选模组缺失时核心模组无法加载。

## 3. 模块职责

本节为目标模块划分。当前原型尚未形成完整的 `CampaignService`、
`MissionDirector`、`VehicleService`、`PopulationScalingService` 或
`RecoveryService` 接口，现状以第 20 节为准。

### 3.1 `CampaignService`

- 战役生命周期：未初始化、初始化中、进行中、结局、无限模式、故障保护。
- 依据游戏日推进“100 天”进度，不依据系统时钟。
- 保存队伍共同进度、当前安全锚点、当前主线区段和全局威胁等级。
- 协调初始化、路线预生成、任务选择、尸潮导演和胜负条件。
- 只在服务器主线程修改状态。

### 3.2 `RouteDirector`

- 以 `campaignSeed + routeIndex + routeRulesVersion` 规划逻辑区段。
- 保证主线路径有且只有一个可继续前进的出口；支线不得替代主线出口。
- 当前锁定 TongDa 1.1.3 的公开 Track Spawner 表面实现 64 格直线区段，并逐格
  验证真实 Create `XO` 轨道后才提交持久化进度。
- 维护列车前方的规划/实现窗口和后方的休眠窗口。
- 生成或激活车站、城市、货场、医院、隧道和桥梁等兴趣点。

### 3.3 `MissionDirector`

- 从数据驱动定义中筛选与当前区段兼容的任务。
- 将随机选择固化到存档，避免重启重抽。
- 执行任务状态机、目标监听、一次性副作用、奖励和失败/恢复规则。
- 为“断轨”和“车站修复开门”等主线障碍持有区段通行锁。

### 3.4 `VehicleService`

- 维护稳定的 `logicalTrainId` 与第三方后端句柄之间的映射。
- 通过 `VehicleBackend` 查询列车位置、车厢、速度、乘员和可驾驶状态。
- 管理默认列车组装、重连定位、失踪检测、紧急制动和救援。
- 不把第三方实体 UUID 当作长期存档主键。

### 3.5 `PopulationScalingService`

- 计算有效队伍人数和难度快照。
- 为任务成本、补给、刷怪上限、尸潮批次和事件持续时间提供统一倍率。
- 对人数快速进出使用滞后和限幅，避免通过反复上下线刷低难度。

### 3.6 `RecoveryService`

- 在加载存档、服务器启动、区块加载和管理员命令时检查不变量。
- 重试未完成的幂等操作。
- 处理默认列车丢失、任务方块被破坏、乘员掉线后无安全落点等情况。
- 进入故障保护模式时阻止继续写坏存档，并提供明确诊断和修复命令。

## 4. 服务器权威与网络协议

### 4.1 权威边界

客户端只能提交“意图”，例如：

- 请求接受可选任务；
- 请求操作任务控制台；
- 请求队长发起紧急救援投票；
- 请求打开任务详情。

服务器收到请求后必须重新验证：

- 玩家 UUID、维度、距离和视线；
- 当前任务阶段；
- 玩家是否真实持有所需物品；
- 队伍权限或投票状态；
- 冷却时间与消息序号；
- 目标方块/列车是否属于当前战役。

客户端不得提交“任务已完成”“奖励数量”“列车坐标”或“轨道已修复”这类结果。

### 4.2 消息类型

建议只同步面向 UI 的稳定领域数据：

- `CampaignSnapshotS2C`：登录/维度切换后的完整摘要；
- `CampaignDeltaS2C`：天数、威胁、当前区段变化；
- `MissionSnapshotS2C` / `MissionDeltaS2C`；
- `TrainAnchorS2C`：供 HUD 指向，不用于客户端移动列车；
- `ActionRequestC2S`：带操作类型、对象 ID 和递增 nonce；
- `ActionResultS2C`：成功、拒绝原因或需要刷新。

消息必须有协议版本、长度上限和集合数量上限。服务端在握手时校验核心版本；不兼容时应给出可读的断开原因，不能依赖反序列化异常。

完整快照只在登录、重连或检测到序号缺口时发送。普通 tick 使用合并后的增量，HUD 不需要每 tick 收包。

## 5. 存档模型

### 5.1 SavedData 划分

权威数据保存在主世界对应的 `DimensionDataStorage` 中。即使列车临时进入其他维度，战役主记录仍只有一份，避免跨维度产生两个真相来源。

建议至少拆成：

| SavedData ID | 内容 | 增长特征 |
| --- | --- | --- |
| `lasttrain_campaign` | 战役状态、天数、初始化事务、队伍、列车记录、活动任务 | 小且频繁修改 |
| `lasttrain_route` | 路线头、区段摘要、兴趣点、通行锁、后端实现状态 | 随里程增长 |

如果长时间无限模式导致 `lasttrain_route` 过大，在存档格式 v2 再将旧区段压缩为范围摘要或按页拆分；MVP 不提前引入自定义数据库。禁止每 tick 把完整区段列表重写为巨大 NBT。

`CampaignSavedData` 的核心字段建议为：

```text
schemaVersion
campaignId
campaignSeed
routeRulesVersion
contentVersion
mode                    // STORY_100_DAYS | ENDLESS
state                   // NEW | INITIALIZING | RUNNING | ENDING | COMPLETE | SAFE_MODE
dayIndex
threatLevel
lastProcessedGameTime
initialization          // 幂等事务
logicalTrain            // 稳定 ID、后端类型、句柄、最后可信锚点、清单摘要
safeAnchor              // 当前站点或列车附近的重连点
players[uuid]           // 首次加入、最后安全点、救援冷却等少量元数据
activeMissions[id]
completedMissionReceipts
worldMutationReceipts
```

`RouteSavedData` 的区段记录建议为：

```text
segmentIndex
segmentSeed
definitionId
definitionVersion
logicalStart / logicalEnd
worldBounds
entryAnchor / exitAnchor
status                  // PLANNED | REALIZING | REALIZED | VALIDATING | READY | BLOCKED | FAILED
routeBackendId
trackBackendId
stationIds
cityIds
missionIds
passageLocks
validationDigest
lastError
```

任何状态变更后调用 `setDirty()`。所有集合设置明确的最大反序列化数量，加载时拒绝或隔离异常值，避免损坏 NBT 导致内存失控。

### 5.2 线程规则

- SavedData 的读写、第三方轨道图访问、方块放置、实体生成只在服务器线程。
- 异步线程只执行不接触世界对象的纯计算，例如基于不可变高度采样快照的路径搜索。
- 异步结果返回主线程后，必须重新校验区块版本和任务版本才能提交。
- 不在异步任务中保留 `Level`、`ChunkAccess`、实体或 BlockEntity 引用。

### 5.3 一次性副作用凭据

每个可能重复执行的世界修改都有稳定的 `operationId`，例如：

```text
init/<campaignId>/starter_station/place
init/<campaignId>/starter_train/assemble
mission/<missionId>/door/open
mission/<missionId>/reward/main
segment/<segmentIndex>/track/repair
```

执行流程统一为：

1. 检查凭据是否已完成。
2. 检查世界是否已经呈现目标结果。
3. 执行最小差异修改。
4. 验证结果。
5. 写入完成凭据并标记 SavedData。

这样可覆盖服务器在步骤 3 与步骤 5 之间崩溃的情况。

## 6. 默认站点与默认列车的幂等初始化

### 6.1 触发时机

在服务器主世界可用后触发初始化，不依赖第一个玩家的客户端事件。初始化事务阶段为：

```text
NOT_STARTED
  → SITE_SELECTED
  → CHUNKS_READY
  → STATION_PLACED
  → TRAIN_MATERIALIZED
  → SUPPLIES_PLACED
  → VERIFIED
  → READY
```

每个阶段都先写入所需的确定性计划，再执行世界修改。重启后从已保存阶段继续，并通过现场标记与适配器查询进行协调，不盲目再放一份。

### 6.2 选址与站点放置

- 默认优先在世界出生点附近的可用走廊选址，最大搜索半径由配置限制。
- 选址结果一旦写入存档就不得因重启或模组更新改变。
- 站点结构包含不可见或受保护的战役标记：`campaignId`、`structureInstanceId`、模板版本。
- 放置前记录边界，确认不会越过世界边界，并使所需区块达到可修改状态。
- 结构模板必须支持旋转，出口锚点必须与第 0 区段的入口锚点对齐。
- 初始化只保护教程所需的关键方块；是否永久防拆由整合包配置决定。

### 6.3 默认列车

列车蓝图是数据驱动的逻辑清单，包括机车、生活车、维修/货运车、初始容器和关键控制方块。组装由当前 `VehicleBackend` 完成：

1. 查找带当前 `logicalTrainId` 标记的现有后端对象。
2. 找不到时检查站内是否已有未组装蓝图。
3. 只补放缺失的蓝图方块和容器，不覆盖玩家已有方块。
4. 请求后端组装并取得短期 `backendHandle`。
5. 校验列车处于起始轨道、可制动、至少有一个驾驶位。
6. 保存映射和最后可信锚点。

如果组装 API 不可用，初始化进入 `SAFE_MODE` 并保留站点与蓝图，不循环生成列车。

### 6.4 初始物资

- 物资容器使用稳定容器 ID，并保存 `suppliesPlaced` 凭据。
- 初始物资按初始化时的有效人数档位生成，但只能发放一次。
- 后加入玩家使用个人“追赶包”策略，而不是再次填充公共箱子。
- 容器已有未知物品时只补齐预定义槽位或改用旁边的补给箱，禁止清空。

管理员命令应提供：

```text
/lasttrain status
/lasttrain init inspect
/lasttrain init retry
/lasttrain validate starter
/lasttrain recover train
```

`retry` 和 `recover` 必须先展示目标与原因；任何可能覆盖玩家建筑的强制模式需要管理员显式确认并生成备份提示。

## 7. 路线区段生成

### 7.1 两阶段模型

路线生成分为不修改世界的“规划”和主线程上的“实现”：

1. `plan(segmentIndex)`：用确定性随机数选择区段类型、长度、转向、坡度预算、车站/城市插槽和任务候选。
2. `realize(plan)`：请求后端放置真实结构与轨道。
3. `validate(segment)`：检查入口到出口的轨道拓扑、净空和关键结构标记。
4. `activate(segment)`：区段进入 `READY`，允许移除上一道通行锁。

单一全局 PRNG 容易因新增一次随机调用而改变后续所有路线。因此每个区段、兴趣点和任务都使用独立派生种子，例如哈希：

```text
segmentSeed = hash(campaignSeed, routeRulesVersion, segmentIndex)
missionSeed = hash(segmentSeed, "mission", slotIndex)
```

### 7.2 生成窗口

默认窗口建议：

- 列车前方至少 2 个区段已 `READY`；
- 第 3 个区段允许处于 `PLANNED/REALIZING`；
- 当前区段与紧邻后方区段保持活动；
- 更远的后方区段卸载，只保留 SavedData 摘要。

实际区段长度、窗口大小和预生成速度必须做成服务端配置。不得因为某个玩家步行或飞行到极远处就改变主线路线头；只有“队伍列车进度”推动主线。

为了避免高速度列车撞进未生成区：

- `VehicleBackend` 提供列车速度和制动能力；
- 前方安全距离低于阈值时设置真实信号/屏障并请求减速；
- 路线准备完毕且验证通过后才解除屏障；
- 屏障必须属于战役系统并有 operationId，不能靠客户端视觉假墙。

### 7.3 区段选择与空间约束

- 主线用粗粒度走廊单元规划，先占用单元再放置细节，避免路线自交。
- 每个计划保存世界边界框和入口/出口切线。
- 高差、海洋、山地可选择桥梁、隧道或绕行模板，但必须有最大计算预算和兜底直线模板。
- 城市作为主线旁的兴趣点生成，不要求整座城市长期加载。
- 结构放置必须检测已存在的战役结构和受保护区域；遇到普通玩家建筑时默认绕行或停在 `BLOCKED` 让管理员处理，不静默覆盖。
- 路线接近世界边界时进入结局站、折返方案或明确的“已到达边界”状态，不能整数溢出。

### 7.4 验证

`TrackAdapter.validateConnection(entry, exit)` 至少验证：

- 入口和出口都解析为有效轨道位置；
- Create 轨道图中存在一条受限长度内的路径；
- 路径属于预期区段且没有错误连接到旧区段；
- 必需道岔方向可用；
- 物理载具净空满足当前列车包络；
- 必须阻塞的任务段在任务完成前确实不可通行；
- 修复任务完成后路径可通行。

失败时保存错误码、相关坐标和后端版本。自动修复只处理明确安全的缺失端点或标记；不大范围重铺世界。

## 8. TongDa Railway 与 Create 轨道适配

TongDa Railway 1.21.1 当前公开说明包含互联铁路、车站、桥隧和玩家接近后生成轨道的 Track Spawner，同时明确世界生成会因高度图预计算变慢。它适合提供线路和车站内容，但核心模组不能假设它天然满足“单一、连续、剧情可控、可无限推进”的全部约束。

### 8.1 接口

```java
interface RouteBackend {
    ResourceLocation id();
    BackendCapabilities capabilities();
    RoutePlanResult plan(RoutePlanRequest request);
    RealizeResult realize(ServerLevel level, PlannedSegment segment, WorkBudget budget);
    InspectResult inspect(ServerLevel level, SegmentRecord record);
    RepairResult reconcile(ServerLevel level, SegmentRecord record, RepairPolicy policy);
}

interface TrackAdapter {
    Optional<TrackAnchor> resolveAnchor(ServerLevel level, BlockPos hint);
    ConnectionReport validateConnection(TrackAnchor from, TrackAnchor to, ValidationRules rules);
    Optional<SignalHandle> createOrFindPassageLock(PassageLockSpec spec);
    boolean isTrainOnSegment(LogicalTrainId trainId, SegmentRecord segment);
}
```

接口名称是设计契约，不代表第三方已经提供同名 API。

### 8.2 TongDa 集成策略

按优先级采用：

1. **官方公开 API/事件**：如果 TongDa 提供稳定的生成请求、端点或结构事件，直接适配。
2. **数据包和配置契约**：用官方支持的结构、标签、配置和 Track Spawner 行为组合区段。
3. **被动兼容模式**：TongDa 负责环境中的普通车站/支线；`lasttrain` 自己实现受控主线。
4. **实验兼容层**：不得已访问内部实现时单独放入版本锁定的兼容 jar，默认关闭，并有启动时精确版本检查。

不应在核心模块中通过 Mixin 或反射修改 TongDa 私有生成器。版本不匹配时应停用该集成并给出诊断，而不是带着未知行为写入存档。

当前原型已固定到 TongDa Railway `1.1.3+mc21.1`，并由
`TongDaTrackBridge` 使用其公开的 `TrackPutInfo.getByDir` 与
`TrackSpawnerBlockEntity.addTrackPutInfo` 方法。适配器每次提交 64 个东向
`XO` 指令；“生成器已排队”只代表待处理，只有相关区块全部加载且 64 个位置均为
真实 Create 轨道时，`RouteDirector` 才递增 `generatedRouteSegment`。生成器完成后
留下的供电玫瑰石英灯是控制标记，不被当作轨道完成证据。接口或配置不匹配时暂停
该段并给出精确诊断，不会回退为核心模组直接铺轨。

### 8.3 Create 集成边界

Create 提供实际轨道方块、车站和轨道图。集成代码负责：

- 将世界坐标解析为轨道锚点；
- 查询轨道图连通性；
- 查找列车和车厢；
- 创建任务信号、临时止轮器或可验证屏障；
- 监听轨道放置/破坏后使对应区段验证缓存失效。

Create 的内部轨道图和列车序列化不是本项目存档格式。升级 Create 前必须运行兼容测试；不能把其对象序列化后原样塞入 `lasttrain` SavedData。

## 9. 物理载具适配层

### 9.1 稳定领域接口

```java
interface VehicleBackend {
    ResourceLocation id();
    VehicleCapabilities capabilities();
    MaterializeResult materializeStarterTrain(StarterTrainSpec spec);
    Optional<VehicleRef> find(LogicalTrainId id);
    VehicleSnapshot snapshot(VehicleRef vehicle);
    ControlResult requestEmergencyStop(VehicleRef vehicle, StopReason reason);
    RelocateResult relocateToAnchor(VehicleRef vehicle, TrackAnchor anchor, RelocatePolicy policy);
    OccupancyResult occupancy(VehicleRef vehicle);
    ReconcileResult reconcile(LogicalTrainRecord record);
}
```

领域层只依赖：

- `logicalTrainId`；
- 当前维度与近似轨道锚点；
- 速度、方向、包络和质量等级；
- 车厢逻辑清单摘要；
- 玩家是否在车上；
- 后端能力标志，例如 `PHYSICAL_COLLISION`、`RELOCATE`、`SAFE_SERIALIZATION`、`CHUNK_TICKETS`。

领域层不得依赖物理子世界类、物理实体类、Create 的具体 Train 对象或某个后端的 UUID 语义。

### 9.2 后端选择

本项目的默认且唯一玩法后端已确定为
[Create Simurail](https://github.com/Crystaelix/Create-Simurail)：

1. 固定上游提交 `e68481d`，mod id 为 `simurail`；
2. 依赖 Create、Create Aeronautics 提供的 Simulated、Sable，并使用
   `simurail:physics_bogey` 与 `simulated:physics_assembler`；
3. 核心只通过注册表 ID 和一个 Alpha 适配器调用，不把测试版实现类型写入存档；
4. 普通 Create 列车只允许用于诊断或救援，不作为玩家可选择的正式玩法后端；
5. Loconautics 与 Ferronautics 不进入本整合包依赖图。

当前开局载具是从 Simurail 自带 Ponder 验证结构扩展出的 24 方块物理验证车，
包含 3×3 无遮挡集合甲板。装配后核心把战役 UUID 与安全集合点写入子世界标签，
把 Sable 子世界 UUID 保存到 `CampaignSavedData`，并添加持久强加载票据。玩家登录或重生时先等待
Sable 恢复跟踪；若不在该车上，则多次尝试回到移动甲板，超时后回退到初始站。
这些代码路径尚未经过进入世界、移动列车、服务器重启和多人汇合验收；当前运行
证据仅到完整模组发现并在 EULA 门前合法停止。该车辆也不是最终美术规格的机车、
生活车和维修货车编组。

以下是目标故障策略，当前尚未实现完整的 `backendId`、`SAFE_MODE` 或跨后端迁移：

- 不自动用另一后端覆盖世界中的列车；
- 战役进入可读的 `SAFE_MODE`；
- 允许管理员安装原依赖后重试；
- 跨后端迁移必须在列车停车、区块加载、清单完成校验并备份存档后显式执行。

### 9.3 不稳定发布风险

Create Simurail 尚未正式发布，因此本项目把它作为明确标注的技术预览依赖。
当前构建同时固定 Git 提交、GitHub Actions artifact ID、压缩包 SHA-256 与运行
JAR SHA-256；没有匹配校验值就拒绝安装。

因此：

- MVP 的核心任务与路线开发不得等待 Simurail API 稳定。
- 开发版从第一天就把 Simurail 物理车纳入验证，不把普通 Create 列车伪装成最终体验。
- 正式物理版的最低验收包括：专服可启动、玩家站在移动列车上不频繁掉落、跨区块稳定、服务器重启后恢复、脱节/脱轨可救援、容器不复制、6 人延迟可接受、许可证允许整合包分发。
- 不把测试群私有 jar、Discord 临时构建或无固定校验值的下载作为依赖。
- 所有物理专属玩法通过能力标志启用；无该能力时提供功能等价但表现简化的任务。

相关公开页面：

- [TongDa Railway](https://www.curseforge.com/minecraft/mc-mods/tongda-railway)
- [Create Simurail 源码项目](https://github.com/Crystaelix/Create-Simurail)
- [Create: Simulated 源码项目](https://github.com/Creators-of-Aeronautics/Simulated-Project)

## 10. 任务系统与状态机

### 10.1 数据驱动定义

目标形态使用数据包 JSON + Codec 加载，代码只实现目标类型和副作用类型。
当前原型仍使用 `MissionType` 枚举和
`ACTIVE → READY_TO_TURN_IN → COMPLETED` 状态。目标定义包含：

```text
definitionId
definitionVersion
category                // MAIN_BLOCKER | OPTIONAL | AMBIENT
selectionConditions
weight
exclusiveTags
objectives[]
transitions[]
worldSetupActions[]
completionActions[]
failurePolicy
scalingPolicy
recoveryPolicy
```

任务实例在首次选择时保存 `definitionId`、`definitionVersion`、随机参数和难度快照。`/reload` 可以影响以后生成的任务，但不得无迁移地改变已经激活的任务。

### 10.2 通用状态机

```text
SELECTED
  → PREPARING
  → AVAILABLE
  → ACTIVE
  → OBJECTIVES_COMPLETE
  → APPLYING_WORLD_RESULT
  → REWARD_PENDING
  → COMPLETED

ACTIVE → FAILED_RECOVERABLE → ACTIVE
任意不可协调状态 → SUSPENDED
```

每次迁移都校验当前状态和版本，并保存单调递增 `revision`。网络层提交旧 revision 的操作会被拒绝并触发刷新。

目标不通过每 tick 扫描整个世界判断。应由方块交互、物品提交、实体死亡、轨道变更、区域进入和定时器等事件更新索引；低频审计用于修复漏事件。

### 10.3 示例：铁路断开

1. 区段实现时保存断轨位置、原轨道规格和前后锚点。
2. `PREPARING` 放置断轨场景及真实通行锁，写入 setup 凭据。
3. 玩家调查后进入 `ACTIVE`，服务器冻结材料需求快照。
4. 材料提交到任务控制器，服务器原子地扣除并累计。
5. 达标后执行轨道修复；不能只播放动画。
6. `TrackAdapter` 验证前后锚点连通。
7. 验证成功才解除通行锁、触发尸潮/奖励并进入完成状态。
8. 若关键轨道被再次破坏，区段标为 `BLOCKED`；根据任务规则复开维护任务，而不是重复发主奖励。

### 10.4 示例：修复车站并开启大门

1. 门、发电机、保险盒和控制台都有稳定对象 ID。
2. 玩家依次恢复燃料/零件、启动电源、守住倒计时。
3. 倒计时按服务器游戏 tick，在无人在线或单人暂停时不推进。
4. 供电结束后服务器检查发电机和控制台仍存在。
5. 开门动作幂等；门已打开则直接协调为完成。
6. 大门后的轨道重新验证后才允许列车通过。
7. 关键方块丢失时从模板只恢复该对象，保留周围玩家改造。

### 10.5 奖励一致性

- 公共奖励进入有稳定 ID 的队伍补给箱，写入一次性凭据。
- 个人奖励按玩家 UUID 保存领取凭据。
- 背包满时不丢在地上，保留 `REWARD_PENDING` 或发送到补给箱。
- 崩溃重试时先检查凭据及目标容器的奖励批次标记，避免复制。

## 11. 玩家人数缩放

### 11.1 有效人数

`effectivePlayers` 不简单等于服务器在线人数。候选玩家需满足：

- 位于战役维度；
- 非旁观者；
- 已加入当前战役；
- 位于列车/当前任务合理范围，或在最近数分钟内参与过任务。

人数使用短时间滑动窗口和上下行滞后。例如玩家断线后保留 60 秒权重，重新连接不会立即重算任务成本。

### 11.2 冻结值与动态值

在任务进入 `ACTIVE` 时冻结：

- 所需材料；
- 固定波次数；
- 一次性奖励档位；
- 必须同时操作的机关数量。

任务进行中可缓慢动态调整：

- 每波普通丧尸数量；
- 同时存活敌人上限；
- 特殊感染者概率；
- 补充波间隔。

推荐从容易测试的分段函数开始，而不是指数膨胀：

```text
材料倍率：1 + 0.55 × (P - 1)，上限 3.75
敌人预算：1 + 0.70 × (P - 1)，上限 4.50
奖励倍率：1 + 0.45 × (P - 1)，上限 3.25
```

其中 `P` 默认限制在 1–6；最终数值由试玩数据替换。敌人数还必须受服务器 TPS 和全局实体预算约束。

### 11.3 单人可完成性

- 所有主线任务必须允许一个玩家依次完成，不能要求两个真实玩家同时按按钮。
- 多人可以分工缩短耗时，但不能跳过关键资源成本。
- 单人守城时减少同时进攻方向，而不是把敌人生命值降到毫无威胁。
- 司机掉线或死亡后其他玩家可接管；单人则提供停稳后的救援机制。

## 12. 断线、重连与后加入

以下登录顺序和相对槽位记录是目标流程，并非当前完整实现：

玩家登录后的处理顺序：

1. 完成协议与整合包版本检查。
2. 读取战役摘要和玩家记录。
3. 验证其原位置是否仍为安全、已加载的实体空间。
4. 如果原位置属于已消失的物理子世界，不直接恢复该坐标。
5. 在当前列车安全点、最近激活车站、最后安全锚点之间依次选择。
6. 发送完整战役和任务快照。
7. 若为首次加入，根据公共进度发一次追赶包和简短教学。

列车移动时断线：

- 保存玩家最后在车上的逻辑标记和相对安全槽位，而非只保存世界绝对坐标。
- 重连时只有 `VehicleBackend` 确认列车存在且能提供安全落点，才传送到车上。
- 失败时落到最近安全站，并给出追赶/救援方式。

与玩家生命周期相关的持久字段目前只有 `CampaignSavedData` 中的一次性物资/
枪械领取 UUID。所有登录或
重生玩家会统一等待 Sable 跟踪，尝试 3×3 甲板的九个安全槽位，超时后在初始站
附近搜索安全落点；尚无协议检查、原位置验证、最近激活站或进度追赶包。

当前原型无人在线时仅保证：

- `CampaignSavedData` 的百日计时不推进；
- The Hordes 依照整合包的 `pauseEventServer = true` 暂停共享尸潮。

`SimurailTrainBootstrap`、`RouteDirector` 和 `MissionWorldDirector` 目前仍在
服务端 tick；已准备的尸群任务可能继续协调实体，Sable 的持久强加载票据也不会
自动释放。无人在线时紧急停车、暂停全部任务世界副作用、释放非必要票据，以及
允许管理员选择继续运行，都是待实现并待联机验收的目标行为。

## 13. 区块加载与性能

### 13.1 区块票策略

以下是目标票据策略。当前原型只给验证车添加 Sable `COMMAND_FORCED` 持久票据，
尚没有有期限的核心自定义票、所有者台账或无人在线释放逻辑。目标只为以下范围持票：

- 当前逻辑列车包络及制动距离所需的小窗口；
- 正在实现或验证的前方区段；
- 活动主线任务的关键区域；
- 正在执行初始化/救援的短期区域。

每张票记录所有者、原因、创建 tick 和过期 tick。服务器停止、任务完成、列车消失或无人在线时释放。不得让每节车厢、每个玩家和物理后端重复叠加无上限区块票。

物理后端若自己管理区块加载，`VehicleCapabilities` 必须报告这一点；核心层改为监控而非再加同等窗口。

### 13.2 工作预算

路线实现采用可中断工作队列，每 tick 限制：

- 新加载/生成区块数；
- 方块放置或结构处理量；
- 轨道图验证节点数；
- 实体生成数；
- 网络增量大小。

超过预算就保存游标，下 tick 继续。不能在一个 tick 内完成整座城市或全路径搜索。

建议初始性能目标：

- 正常行驶时服务器 MSPT 的核心模组增量 P95 小于 5 ms；
- 区段生成峰值不连续超过 50 ms；
- 6 名玩家、活动尸潮和物理列车下保持可玩的 20 TPS，短峰值可诊断；
- 无活动任务时不遍历全服所有实体或已生成区段。

这些是验收目标，不是尚未测量的承诺。

### 13.3 实体与尸潮预算

- 每个事件有本地敌人预算，全战役有全局预算。
- 只在玩家可到达但非直接视野内的已加载位置生成。
- 远离所有战役参与者、任务结束且不具名的事件实体可安全回收。
- 特殊感染者、掉落物和路径寻找分别设上限。
- 尸潮导演根据 TPS 降低新生成速率，不能通过删除正在战斗的敌人掩盖性能问题。

### 13.4 诊断

提供 `/lasttrain debug perf` 输出：

- 活动区块票及原因；
- 路线工作队列长度；
- 当前/前方区段状态；
- 任务实体数；
- 后端列车句柄和最后快照年龄；
- 最近验证耗时与失败原因。

日志必须使用限频，不能每 tick 重复打印同一断轨或缺失列车错误。

## 14. 故障恢复与不变量

每次加载和低频审计检查：

1. 只有一个活动战役记录。
2. 只有一个活动 `logicalTrainId`；后端重复对象进入隔离列表。
3. 当前路线头之前的所有主线区段都有连续索引。
4. `READY` 区段有有效入口、出口和验证摘要。
5. 已完成任务的主奖励凭据最多一个。
6. 活动主线障碍必有通行锁；完成后锁应解除。
7. 初始化 `READY` 时站点、列车或明确的救援锚点必须存在。

恢复优先级：

1. 仅重建缓存和索引；
2. 重新绑定现有世界对象；
3. 补齐单个缺失任务对象；
4. 将列车安全迁移到最近已验证锚点；
5. 进入 `SAFE_MODE` 并等待管理员；
6. 最后才允许显式的强制重建。

禁止自动删除无法识别的列车、方块或玩家建筑。

## 15. 存档迁移和版本锁定

### 15.1 版本字段

同时记录：

- `schemaVersion`：NBT 结构版本；
- `contentVersion`：任务、结构和战役内容版本；
- `routeRulesVersion`：确定性路线规则版本；
- `backendId` 和后端版本；
- 创建世界时的关键依赖版本快照。

### 15.2 迁移规则

以下是目标迁移规则。当前代码只有 schema 6 字段和 v5→v6 终局状态迁移；
尚无通用逐版链，也不会对高于当前版本的 schema 执行 fail-closed。

- 迁移链逐版执行，例如 v1 → v2 → v3，不写跨多版猜测逻辑。
- 每一步纯粹、幂等、有单元测试，并保留迁移前版本号直到验证完成。
- 迁移前提示备份；失败时不覆盖原数据并进入 `SAFE_MODE`。
- 不支持静默降级。较旧核心模组看到较新 schema 时拒绝写入。
- 已生成区段保留自己的定义版本；更新只影响未规划区段。
- 活动任务保留定义快照所需参数，数据包删掉旧定义时仍能完成或进入受控取消流程。
- 路线算法更新时增加 `routeRulesVersion`，不得重新解释已生成区段的种子。

### 15.3 后端迁移

从 Create 原生列车迁移到物理列车不是普通 schema 迁移。它是管理员触发的世界操作：

1. 要求列车停在迁移站且没有未结算任务。
2. 创建世界备份。
3. 导出逻辑车厢清单、容器摘要和乘员状态。
4. 在隔离锚点由新后端创建候选列车。
5. 校验方块数、容器哈希、驾驶位和包络。
6. 明确提交后才切换 `backendId`。
7. 旧对象的处理遵循后端规则并记录审计凭据。

任何不能无损映射的方块必须先列出，不自动丢弃。

## 16. 配置边界

配置分三类：

- **Server config**：路线窗口、无人在线是否暂停、队伍人数上限、任务倍率、区块预算、救援策略。
- **Common config**：仅放不影响存档权威的共同参数。
- **Client config**：HUD、提示音、颜色和可访问性。

会改变路线确定性或存档语义的设置在创建战役时复制进 SavedData，之后更改配置不能悄悄重写旧战役。管理员必须通过迁移命令显式采用新规则。

数据包负责：

- 任务定义；
- 站点/城市模板引用；
- 默认列车逻辑蓝图；
- 初始补给和奖励表；
- 区段权重与出现条件；
- 物品/方块兼容标签。

第三方模组存在条件应使用 NeoForge 支持的数据加载条件；代码侧仍需做能力检查，不能只看某个 mod id 就假设 API 可用。

## 17. 测试策略

### 17.1 单元测试

- 区段/任务派生种子在固定输入下稳定。
- 所有任务状态迁移，包括非法迁移。
- 人数缩放、滞后和上下限。
- SavedData v1/v2 等迁移样本。
- 一次性凭据在任意步骤重入都不重复奖励。

### 17.2 GameTest / 集成测试

- 新世界初始化恰好生成一个站和一列车。
- 在每个初始化阶段模拟重启后能继续。
- 断轨任务完成前不可通、完成后轨道图连通。
- 车站门任务关键方块丢失后能最小恢复。
- 生成至少 20 个连续测试区段并验证拓扑。
- 列车丢失后可绑定或救援。
- 玩家在移动列车上断线、重连到安全位置。

### 17.3 部署矩阵

每个发布候选至少测试：

| 场景 | 人数 | 必测内容 |
| --- | ---: | --- |
| 单人 Integrated Server | 1 | 暂停、保存退出、重进、推进天数 |
| 单人开放 LAN | 2–4 | 中途加入、房主保存退出、权限 |
| Dedicated Server | 1 | 无人在线暂停、重启 |
| Dedicated Server | 2–6 | 尸潮、任务同步、司机掉线、性能 |

物理后端矩阵另加：

- 列车跨越区块边界时上下车；
- 服务端强制重启后恢复；
- 高延迟客户端；
- 车厢拆装、容器和死亡掉落；
- 脱轨、断轨、高速接近未就绪区段；
- 24 小时耐久运行和多次自动保存。

建议加入“加速百日”测试模式，只改变测试环境中的日长，跑完整条主线状态机。

## 18. MVP 开发阶段

### 阶段 0：依赖与 API 探针

交付：

- 锁定 Minecraft、NeoForge、Create、TongDa 的精确版本与校验值；
- 最小专服能启动；
- 验证 Create 轨道图、列车查询和结构放置所需的公开集成面；
- 确认 TongDa 是否有可用公开 API；
- 对候选物理后端记录 mod id、许可证、发布渠道和服务端结论。

退出条件：不再依赖“猜测存在”的方法或模组。

### 阶段 1：服务器骨架与初始化

交付：

- Campaign/Route SavedData；
- 协议握手和最小 HUD 快照；
- 幂等起始站、蓝图、初始物资；
- `CreateTrainBackend`；
- 状态、验证和恢复命令。

退出条件：单人、LAN、专服连续十次新建/重启均不复制站点、列车或物资。

### 阶段 2：有限但可扩展的路线

交付：

- 确定性区段规划器；
- 直线、桥梁/隧道兜底、普通站至少三类模板；
- 前方窗口、通行锁、轨道连通验证；
- TongDa 的公开 API 适配或明确的被动兼容模式。

退出条件：自动生成并驾驶通过 20 个连续区段，重启后结果不变。

### 阶段 3：任务竖切

交付：

- 数据驱动任务状态机；
- “断轨修复”和“恢复车站电力开门”两条完整任务；
- 一次性奖励、失败恢复和任务 HUD；
- 1–6 人缩放。

退出条件：在三种部署形态下完成任务，重启点覆盖每个关键阶段且无复制/卡关。

### 阶段 4：联机韧性

交付：

- 后加入追赶；
- 移动列车断线重连；
- 队长/投票和权限；
- 列车救援；
- 区块票与性能诊断。

退出条件：6 人专服测试中司机断线、成员频繁进出和服务器重启均可继续战役。

### 阶段 5：物理后端

交付：

- 一个通过发布与许可证审核的 `VehicleBackend`；
- 物理列车默认蓝图；
- 脱轨、子世界、区块加载和重定位处理；
- Create 后端到物理后端的显式迁移工具（若需要）。

退出条件：通过第 17 节物理验收矩阵。未达到时继续以“技术预览”标记，不能作为稳定版。

### 阶段 6：百日内容与无限模式

交付：

- 完整日程、威胁曲线、城市/站点池；
- 关键剧情区段保证出现；
- 第 100 天终局；
- 通关后的无限模式和长期存档压缩。

退出条件：完成一次加速百日自动/人工混合回归，并验证通关后继续生成。

## 19. 主要风险与对策

| 风险 | 影响 | 对策 / 决策闸门 |
| --- | --- | --- |
| Create Simurail 尚未正式发布 | API/存档可能突变，CI artifact 可能过期 | 固定提交和双重哈希；适配器隔离；缓存或从固定源码构建；每次升级跑专服验收 |
| Simurail 物理结构组装失败 | 玩家没有默认载具 | 保存独立装配状态和失败次数；保留站台现场；提供管理员诊断与后续救援命令 |
| TongDa 生成器不提供剧情级控制 API | 无法保证单一连续主线或指定任务点 | TongDa 被动提供环境/支线；核心自有受控主线 RouteBackend |
| TongDa 高度图预计算与城市生成过重 | 区段生成卡顿、列车追上生成窗口 | 分 tick 预算、提前 2–3 段生成、速度屏障、可选服务端预生成 |
| Create 内部轨道图 API 变化 | 升级后验证和列车绑定失效 | 版本锁定、集成包隔离、固定地图 GameTest、升级闸门 |
| 物理列车跨区块/重启丢失 | 玩家、物资或主基地永久丢失 | 逻辑列车为事实来源、可信锚点、清单摘要、显式救援 |
| 任务副作用在崩溃时重复 | 无限奖励、重复门/列车 | operationId 凭据、现场协调、分阶段事务 |
| 玩家破坏关键任务方块 | 主线卡死 | 稳定对象 ID、最小恢复、保护标签、管理员诊断 |
| 人数进出影响成本 | 玩家通过掉线降低难度或导致任务暴涨 | 激活时冻结成本，动态项使用滞后和限幅 |
| 无限区段记录持续增长 | NBT 过大、保存变慢 | 只存摘要；长期模式按范围压缩/分页，先做体量监控 |
| 物理后端与核心重复加载区块 | 内存与 TPS 恶化 | 能力协商、单一票所有者、票诊断与硬上限 |
| 集成服务器暂停语义与专服不同 | 单人计时被后台偷跑或多人节奏不一致 | 只按服务器游戏 tick；默认无人在线暂停 |
| 高速列车进入未就绪路线 | 脱轨或坠入空区块 | 制动距离监控、真实通行锁、验证后放行 |
| 模组升级改变结构/任务内容 | 旧世界被重写或任务无法完成 | 区段与任务实例固化版本，迁移链，不重新生成旧段 |
| 客户端伪造任务消息 | 奖励作弊或远程操作 | 只提交意图，服务器复验位置、物品、权限和 revision |

## 20. 当前兼容性原型切片

当前仓库实现范围为：

1. 一个服务器权威核心模组和一份 `CampaignSavedData`；
2. 一个代码生成的固定起始站、共享补给箱和每玩家一次性物资；
3. 一辆由 Simurail Ponder 结构扩展出的 24 方块 Sable 物理验证车；
4. 通过 TongDa Track Spawner 实现的 64 格东向直线区段，保持列车前方两段并设
   400,000 段安全上限；
5. 断轨、供电、站门、补给回收和尸群清理五种代码定义的任务现场；
6. `/lasttrain status`、战役推进和任务管理命令；
7. 1–6 人有效队伍数滑窗和按创建时人数冻结的任务目标缩放；材料型目标按
   1+0.55×(P-1)（上限 3.75）扩展现场，尸群预算按 1+0.70×(P-1)（上限 4.50）
   计算，并将有效人数同步为 In Control 的 `lasttrain_players`；
8. 普通任务宽限期降级：超时后清理路障并加 6 点威胁，终局任务不可被时钟
   失败；`/lasttrain mission fail` 提供同策略的管理员手动入口；
9. 抽象后方尸潮：持久化关注度和追击距离，停车/噪声缩短距离、前进拉开距离，
   归零时生成可交付的僵尸封锁，完成或降级后恢复；数值同步为 In Control 的
   `lasttrain_attention` 与 `lasttrain_pursuit`；
10. 核心单元测试、安装器故障测试，以及不接受 EULA 的专服模组发现冒烟测试。

该切片已经覆盖构建、依赖固定、安装安全和纯状态策略；尚未验证进入真实世界后的
列车装配/行驶、Create 轨道图拓扑、单人/LAN/多人重连、长期存档或百日完整流程，
因此仍是兼容性原型，不是可游玩的稳定发布版。
