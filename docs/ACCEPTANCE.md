# 规格与质量验收

状态日期：2026-08-30。
适用版本：`lasttrain 0.1.0-alpha.4` / Packwiz 包 `0.1.0-alpha.4`。
依据文档：`docs/GAME_DESIGN.md`、`docs/TECHNICAL_ARCHITECTURE.md`、
`docs/ROADMAP.md`、`docs/COMPATIBILITY.md`。

## 0. xhigh 重构一致性复核（2026-08-30）

本节记录当前 `feat/xhigh-rebuild` 代码切片的静态审查与单元回归；下方 77 项记录
保留为 2026-08-15 后方尸潮切片的历史证据，不再代表当前测试总数。

| 设计/验收项 | 当前实现证据 | 结论 |
| --- | --- | --- |
| 五感染阶段效果进入真实数值路径 | `InfectionPolicy.Stage.effects()` 同时驱动最低关注度、停车追击消耗、尸群目标倍率和事件概率；`IntegrationBridge` 同步阶段；既有感染策略/存档测试 | PASS（纯策略/事件接线） |
| 至少三类主线障碍 | 断轨、供电、站门、线路清障、尸群封锁均为真实世界目标；`TUNNEL` 关键任务改用 `TRACK_CLEARANCE`；清障布局/宽限/里程门测试 | PASS（代码世界适配器，仍需实机） |
| 两类可选任务 | 搜救与车厢回收保持并行槽、接受/拒绝/超时/清理路径 | PASS（策略/存档/适配器测试） |
| 主线奖励与重启恢复 | 主线交付先写 `PENDING` 收据再由共享 outbox 整批投递；补给回收使用现场桶，避免双发；保存重载与 payload 合法性测试 | PASS（纯策略/存档） |
| 五阶段/任务/奖励语言键 | `TranslationKeys` 集中领域映射；测试验证 en_us/zh_cn 键集合相同且覆盖所有枚举值 | PASS（自动化资源契约） |
| 无人在线暂停 | 事件层在 0 名非旁观者时暂停路线、主任务、可选任务、outbox 与清理世界副作用；`ServerActivityPolicyTest` | PASS（控制流/纯策略） |
| 状态与存档诊断 | `/lasttrain status detail` 输出同 tick 不可变快照；`/lasttrain validate save` 和加载日志使用同一只读完整性策略 | PASS（纯策略/编译） |
| 列车恢复 | 丢失/静止判定、代价、冷却、锚点钳制和 `SAFE_MODE` 已实现；`recover train` 仍只登记逻辑救援 | PARTIAL：物理归位/重建仍待后端与实机验收 |
| 存档兼容 | schema 12 为任务/延期清理快照增加有界实体 UUID 索引；旧档在现场 96 格 AABB 内执行一次兼容认领 | PASS（保存重载回归） |

当前全量 JUnit 回归为 `385 passed / 0 failed / 0 errors / 0 skipped`；执行命令为
`./gradlew cleanTest test --rerun-tasks --no-build-cache -q`。本轮遵守不启动游戏、
不接受 EULA 的限制，因此世界内物理行为仍以第 5 节的未覆盖边界为准。

## 1. 后方尸潮切片 TDD 历史记录（2026-08-15）

本轮按红-绿-重构顺序开发“抽象后方尸潮”：

1. **红**：先添加 `PursuitPolicyTest` 与 `CampaignSavedDataPursuitTest`。
   此时 `PursuitPolicy` 不存在，`compileTestJava` 按预期失败。
2. **绿**：实现 `PursuitPolicy` 纯策略和 `CampaignSavedData` 持久化/采样/
   围攻触发逻辑，两个新测试类转绿。
3. **重构/集成**：把枪声、爆炸、状态命令和 In Control 同步接到同一策略，
   随后运行全部 77 个单元测试，结果 `77 passed / 0 failed / 0 skipped`。

## 2. 规格验收矩阵

| 编号 | 设计规格 | 实现/证据 | 验收结果 |
| --- | --- | --- | --- |
| S1 | 关注度 0–100，分为安静/受注意/聚集/围攻/失控 | `PursuitPolicy.AttentionLevel`，`PursuitPolicyTest` | PASS（纯策略） |
| S2 | 停车提高关注度、缩短追击距离；前进冷却关注度、拉开距离 | `PursuitPolicy.sample`，每 20 活动 tick 采样；`PursuitPolicyTest`、`CampaignSavedDataPursuitTest` | PASS（纯策略） |
| S3 | 追击距离 0 时触发高压围攻，不删除列车或世界 | 归零且无活动任务时创建 `ZOMBIE_BLOCKADE`；`TickOutcome.SIEGE_TRIGGERED`；测试 | PASS（纯策略/存档） |
| S4 | 完成围攻后恢复追击距离；降级路径也恢复部分距离 | `PURSUIT_AFTER_SIEGE` / `PURSUIT_AFTER_SIEGE_FALLBACK`；测试 | PASS（纯策略/存档） |
| S5 | 枪声与爆炸提高关注度 | TaCZ 桥接和爆炸事件调用 `registerGunfire`/`registerExplosion`；测试 | PASS（事件接线） |
| S6 | 无玩家在线时压力不变化 | 压力采样位于 `tick(activePlayers)`，且 `CampaignEvents` 无有效玩家时提前返回 | PASS（控制流审查） |
| S7 | 关注度不能低于章节下限 | `PursuitPolicy.minAttention`；测试 | PASS（纯策略） |
| S8 | 数值同步给第三方难度系统 | `IntegrationBridge` 同步 `lasttrain_attention`、`lasttrain_pursuit`、`lasttrain_players`、`lasttrain_day`、`lasttrain_threat` | PASS（接线审查） |
| S9 | 旧存档缺省安全 | 缺失 `attention`/`pursuit_distance` 时回退初始值，`last_pursuit_route_segment` 对齐当前路线；测试 | PASS（存档兼容） |
| S10 | 状态命令可诊断 | `/lasttrain status` 显示关注度等级与追击距离，中英文语言键齐全 | PASS（编译/JSON 校验） |

## 3. 回归规格

| 类别 | 覆盖点 | 结果 |
| --- | --- | --- |
| 百日终局 | 完整在线计时 + 终局任务交付双门，schema 5→6 迁移 | 77 项单元测试 PASS |
| 任务 | 目标冻结、现场扩展、事件进度、方块保护、宽限降级 | 77 项单元测试 PASS |
| 路线 | 64 格几何、进度钳制、TongDa 提交判定 | 77 项单元测试 PASS |
| 玩家回归 | 列车安全点与初始站回退时序 | 77 项单元测试 PASS |
| 安装器 | 上游固定、哈希校验、回滚、锁、故障保留现场 | 3 个 shell 测试 PASS |
| 真实模组发现 | 完整依赖树加载至 `eula=false` 合法停止 | 本地专服冒烟 PASS |

## 4. 质量门槛

| 门槛 | 命令/方法 | 结果 |
| --- | --- | --- |
| 构建 | `./gradlew build` | PASS |
| 单元测试 | `./gradlew test`，JUnit 5，77 项 | PASS（0 失败） |
| 语言文件 | `jq empty` 校验 `en_us.json`、`zh_cn.json` | PASS |
| 补丁卫生 | `git diff --check` | PASS |
| 安装器安全测试 | `scripts/tests/{fetch-simurail,install-dev-client,install-dev-server}-test.sh` | PASS |
| 专服发现冒烟 | `run/dev-server/run.sh nogui`，确认 `Last Train 0.1.0-alpha.4`，随后停在 EULA 门前 | PASS |
| 工作区状态 | 提交后 `git status --short` 为空 | PASS |

## 5. 未覆盖边界（诚实声明）

以下项目仍未通过进入真实世界后的运行验收，不属于本轮可承诺范围：

- 接受 EULA、创建世界、实际启动战役；
- Simurail 装配/行驶、Sable 子世界重启恢复；
- TongDa 世界内铺轨、TongDa/Create 轨道图拓扑；
- TaCZ 实弹交互与移动列车内射击；
- 单人/LAN/多人重连和百日耐久。

### 5.1 阶段5 物理后端未完成

以下物理后端能力依赖 Create/TongDa 的真实轨道后端，当前实现仅为逻辑层，
明确留待实弹环境开发验收，未写任何“假装完成”的代码或测试：

- **真实轨形**：曲线、道岔、斜向轨道尚未实现。当前主轨恒为东向 64 格
  直线（XO 形状），`RouteSegmentLayout` 的主线步骤全部朝东，未来转向
  模板只改变步骤列表，物理曲线形状仍需 TongDa 后端支持；
- **CITY_BYPASS 支线与主轨的物理交汇**：城市支线以独立直段（ZO 形状）
  从支线出口的起点格直接提交，支线起点格与主轨所在格可能重合，真实
  的道岔/交汇结构尚无后端能力支撑，留待实弹环境验证；
- **BRIDGE_TUNNEL 真实桥隧结构**：当前桥梁/隧道区段仅以更密的支撑柱
  间距区分，真实桥面/隧道洞体结构依赖 Create/TongDa 后端能力，未实现。

因此本版本仍为兼容性原型（`alpha`），不是可游玩稳定版。规格矩阵中标注
“纯策略/存档”的项目只验证了逻辑层，不替代真实世界验收。
