# 末班列车：百日惊变

《末班列车：百日惊变》（Last Train: 100 Days）是一套面向 Minecraft
1.21.1 NeoForge 的合作生存整合包与配套核心模组。

目标体验是：玩家以一列物理化火车作为移动基地，沿持续生成的 Create 铁路前进，在车站、
城市和线路设施之间搜索补给、修复铁路并抵御逐日增强的感染者。第 100 天前，
小队必须让列车抵达并突破最终隔离站。

## 目标部署模式

- 单人世界；
- 单人世界开放联机；
- NeoForge 专用服务器。

三种模式共用同一套服务器权威逻辑和存档格式。任务、路线、天数和列车状态
始终保存在世界存档中，而不是某个玩家身上。

## 目标玩法支柱（含规划内容）

- 火车既是载具，也是唯一可靠的长期基地；
- 通达铁路提供 Create 线路、车站、桥梁和隧道基础；
- 线路导演保证主线连续，并在前方安排城市、任务与事件；
- 动态任务包含断轨、封锁站门、停电、塌桥、堵塞隧道和补给回收；
- 后方尸潮前线迫使玩家在“停车搜刮”和“继续逃亡”之间取舍；
- 默认载具锁定为 Create Simurail 测试构建：列车是可碰撞、可登车建造的
  Sable 物理子世界，而不是普通 Create 的视觉实体；

## 当前状态

项目已进入可构建的兼容性原型阶段。当前开发分支提供：

1. 可在单人、局域网和专用服务器中共用的 `lasttrain` 核心；
2. 服务器权威、带 schema 6 和 v5→v6 终局迁移的战役存档，以及仅在玩家在线
   时推进的百日时钟；
3. 随机线路/每日任务状态机及 `/lasttrain` 管理命令；
4. 幂等生成的初始站台、共享补给箱与每位玩家一次性初始物资；
5. 锁定到提交与 SHA-256 的 Create Simurail 测试构建，以及 Packwiz 锁定的
   铁路、城市、尸潮和枪械依赖；
6. 通过通达铁路 1.1.3 的 Track Spawner 向前两段增量生成线路的代码路径；每段
   只有在 64 格真实 Create 轨道全部校验通过后才写入战役进度，世界内验收待办；
7. 已物理化的断轨、供电、站门、补给回收和尸群清理任务现场；
8. TaCZ 初始手枪/弹药，以及枪声吸引已加载僵尸的服务端联动；
9. In Control 第 25/50/75 天强度分层，以及要求“第 100 天完整在线计时 +
   完成核心生成的 12 只僵尸封锁任务并手动交付”两项同时满足的终局状态原型；
10. 登录/重生后尝试返回 3×3 移动甲板、超时回初始站的联机代码路径；独立
    服务端安装器具备哈希校验、锁、受管理树回滚和失败记录，Prism 安装器目前
    提供版本固定、哈希校验、共享构建锁及旧核心/Simurail 备份；
11. 1–6 人有效队伍数滑窗（升/降均需连续 60 秒确认后生效），新任务按冻结
    人数缩放材料目标或尸群预算，任务现场会按缩放后的目标数量扩展，并把
    `lasttrain_players` 同步给 In Control；
12. 普通任务宽限期与可恢复降级：超时后导演清理路障并加 6 点威胁，终局任务
    不会被时钟自动失败；管理员可用 `/lasttrain mission fail` 手动走同一条
    降级路径。

## 当前验证边界

已在本地验证 Gradle 构建与单元测试、安装器故障路径，以及真实专服完整模组
发现后在 `eula=false` 合法停止；GitHub 另已配置每周/手动的同类发现测试。单元
测试已覆盖有效人数滑窗、任务目标冻结、扩展任务现场布局和任务宽限降级。
尚未接受
EULA、创建世界或启动实际战役，因此 Simurail 装配/行驶、TongDa 世界内铺轨、
TaCZ 实弹交互、单人/LAN/多人重连和百日耐久均未完成运行验收。

当前内容仅用于新建测试世界或备份副本。起始站、直线路廊和任务现场会清理
预定区域内的方块，尚不会避让玩家建筑。开局载具是 24 方块物理验证车，不是
最终机车、生活车和维修货车编组；城市也尚未由线路导演锚定，终局尚无最终
隔离站结构和列车到站条件。

玩法设计见 [GAME_DESIGN.md](docs/GAME_DESIGN.md)，技术架构见
[TECHNICAL_ARCHITECTURE.md](docs/TECHNICAL_ARCHITECTURE.md)，模组兼容策略见
[COMPATIBILITY.md](docs/COMPATIBILITY.md)，开发阶段见
[ROADMAP.md](docs/ROADMAP.md)。

## 开发环境

- Java 21；
- Minecraft 1.21.1；
- NeoForge 21.1.244；
- Gradle Wrapper（仓库内提供）。

项目不把第三方模组 JAR 提交进 Git。正式发布模组由 Packwiz 在玩家本地取得；
尚未正式发布的 MIT 许可 Create Simurail 由安装脚本从其上游 GitHub Actions
取得（无 GitHub CLI 登录时从同一固定提交构建），并校验提交与 SHA-256。

## 构建

```bash
./gradlew build
```

开发专服安装（不会自动接受 Minecraft EULA）：

```bash
./scripts/install-dev-server.sh ./run/dev-server
```

Prism 开发客户端/单人世界安装（不会登录或启动游戏）：

```bash
./scripts/install-dev-client.sh ./run/dev-client
```

详细步骤见 [INSTALL_CLIENT.md](docs/INSTALL_CLIENT.md)。

开发客户端/单人世界安装为受管理的 Prism Launcher 实例：

```bash
./scripts/install-dev-client.sh \
  /path/to/PrismLauncher/instances/lasttrain-dev
```

客户端脚本不会登录账户、启动 Minecraft 或替用户接受任何外部条款。完整说明见
[客户端与单人世界开发安装](docs/INSTALL_CLIENT.md)。项目自有内容采用
[MIT 许可](LICENSE)；第三方模组仍受各自条款约束，详见
[第三方声明](THIRD_PARTY_NOTICES.md)。
