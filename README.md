# 末班列车：百日惊变

《末班列车：百日惊变》（Last Train: 100 Days）是一套面向 Minecraft
1.21.1 NeoForge 的合作生存整合包与配套核心模组。

玩家以一列物理化火车作为移动基地，沿持续生成的 Create 铁路前进，在车站、
城市和线路设施之间搜索补给、修复铁路并抵御逐日增强的感染者。第 100 天前，
小队必须让列车抵达并突破最终隔离站。

## 支持模式

- 单人世界；
- 单人世界开放联机；
- NeoForge 专用服务器。

三种模式共用同一套服务器权威逻辑和存档格式。任务、路线、天数和列车状态
始终保存在世界存档中，而不是某个玩家身上。

## 核心支柱

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
2. 服务器权威、可迁移的战役存档与仅在玩家在线时推进的百日时钟；
3. 随机线路/每日任务状态机及 `/lasttrain` 管理命令；
4. 幂等生成的初始站台、共享补给箱与每位玩家一次性初始物资；
5. 锁定到提交与 SHA-256 的 Create Simurail 测试构建，以及 Packwiz 锁定的
   铁路、城市、尸潮和枪械依赖；
6. 向前两段增量生成、随队伍继续延伸的保底 Create 线路，并让通达铁路和
   Lost Cities 负责沿线随机铁路/城市环境；
7. 已物理化的断轨、供电、站门、补给回收和尸群清理任务现场；
8. TaCZ 初始手枪/弹药，以及枪声吸引已加载僵尸的服务端联动；
9. In Control 百日强度分层、Prism 单人客户端和独立服务端安装脚本。

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
