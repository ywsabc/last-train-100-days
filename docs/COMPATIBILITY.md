# 兼容性基线

状态日期：2026-08-01。

## 目标平台

| 组件 | 目标 |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge 21.1.244 |
| Java | 21 |
| 机械动力 | Create 6.0.10 |
| 线路生成 | TongDa Railway 1.1.3 |
| 物理引擎 | Sable 2.0.3 |
| 物理列车 | Create Simurail `e68481d` Alpha |
| 枪械 | 非官方 TaCZ 1.1.8 hotfix r5 |

1.20.1 Forge 的通达铁路移植版不属于目标平台，不与 1.21.1 物理列车方案混用。

## 通达铁路

[TongDa Railway](https://www.curseforge.com/minecraft/mc-mods/tongda-railway)
负责在世界中生成 Create 轨道、车站、桥梁和隧道。它是线路素材和生成基础，
不是百日战役的完整路线导演。

核心模组仍需负责：

- 检查下一段线路是否真正连通；
- 在必要时生成过渡段或修复接缝；
- 约束主线方向，避免随机网络破坏战役节奏；
- 把城市和任务锚点附着到已确认的线路节点；
- 为断轨任务区分“剧情断轨”和“生成错误”。

当前 64 格主线路段不由核心直接铺设；除起始验证轨道和任务修复点外，前方主线
由 TongDa Track Spawner 物化。锁定版本适配器通过公开的
`TrackPutInfo.getByDir` 和 `TrackSpawnerBlockEntity.addTrackPutInfo` 提交每段
64 格东向轨道；排队、生成中、区块未加载、形状冲突与真实完成分别处理。只有
64 个已加载位置全部是 Create `XO` 轨道后才推进存档中的已生成区段。

## 物理列车

### Create Simurail（已选定）

[Create Simurail](https://github.com/Crystaelix/Create-Simurail)
是用户指定的航空学物理列车实现。当前基线固定到 2026-07-29 通过上游 CI 的
提交 `e68481dcf56de6a020e42880792526c77b017060`：

- Actions artifact ID：`8738923712`；
- artifact ZIP SHA-256：
  `6e49ab6573d027456e8f935ec9c3b29a665ff31feed612ce5b6256c0c9bbabd4`；
- 运行 JAR SHA-256：
  `d85ee304d972397807f801aae73a167163729628e382b26db0fcc0c52834e602`；
- 解压后按路径排序的内容 SHA-256：
  `aa30b9b0a4e27ba90aedb6e4ff171a94e88c01c7f0c95ef2d57bd9a253f3d63b`；
- 固定源码归档 SHA-256：
  `8417fadd7f6a5afa7322e191326f423344d68937f724ebf684485d02e8de9144`。

它使用 `simurail:physics_bogey` 在 Create 轨道上约束真实 Sable 子世界，车辆
通过 Create: Simulated 的物理组装器生成。因此初始列车不会调用 Create 原生
列车装配，也不会依赖 Ferronautics 的普通列车转换钩子。

Simurail 尚无正式 Release。安装脚本优先下载并校验上述 CI 构建；CI artifact
过期或 GitHub CLI 不可用时，从同一提交的已校验源码归档构建。源码构建可能
只有 ZIP 顺序/时间戳不同，因此以解压内容哈希复验；任何 class、资源或元数据
变化都会拒绝安装。升级只能在独立兼容性变更中进行，不能自动追随 `main`。

### Loconautics（未采用）

[Loconautics](https://www.curseforge.com/minecraft/mc-mods/loconautics)
同样计划让列车成为 Sable 物理对象，但仍未提供可安装文件，且不是用户指定
实现，因此不进入当前依赖链。

### Ferronautics（未采用）

[Ferronautics](https://www.curseforge.com/minecraft/mc-mods/ferronautics)
只把普通 Create 车厢内容搬入 Sable 子世界，底层列车仍由 Create 轨迹模拟。
这不满足本项目指定的 Simurail 真实物理转向架方案，已从整合包移除。
Sable Pathfinder 1.4.0 暂留兼容性测试，用于感染者跨父世界/移动子世界寻路。

## 城市与感染者

- Lost Cities 8.3.10 已配置项目自有 `lasttrain` 稀疏城市参数；
- Lost Cities 的铁路、车站、高架和随机爆炸已配置关闭，避免与通达铁路争夺空间；
- In Control 10.2.6 已配置只允许僵尸系敌对生物，并按核心同步的
  `lasttrain_day` 在第 25、50、75 天提高血量、伤害、速度和数量；
  核心同时同步平滑后的 `lasttrain_players` 有效队伍数，供后续人数档位使用；
- The Hordes 1.6.3d 已配置每十天触发联机共享尸潮并在离线时暂停；
- Zombie Awareness 当前在开发包中启用，发布前必须通过 80/120/160 实体压测，
  否则从默认包移除。

以上配置尚未经过进入世界后的运行验收。

## 枪械

当前开发基线固定为 TaCZ 非官方 NeoForge 移植 r5 和
TACZ Aeronautics Compat 1.8.0。TaCZ JS 在没有自定义枪械脚本前不加载，以减少
必需 mixin 面积。兼容桥只承诺弹丸与物理结构的基础碰撞，因此
移动列车内外互射、倍率镜、火箭/榴弹、区块边界和重连都是发布阻断项。

TaCZ 资源、Sable 及多个兼容桥不能由本项目重新托管；GitHub 仅保存 Packwiz
元数据、官方来源、版本 ID 与哈希。Simurail 本身采用 MIT 许可，但本项目仍只
保存其构建坐标和哈希，不提交上游二进制。

## 载具适配策略

以下是目标 `TrainBackend` 能力边界，尚未完整抽象为仓库中的接口：查找/生成、
位置/速度/质量、乘员、锁定/恢复、车厢、重定位和物理能力报告。

当前原型只由 `SimurailTrainBootstrap` 与 `SableTrainTracker` 实现其中的装配、
Sable UUID/位置、玩家 tracking、安全集合点和持久票据子集。反射调用失败会被
核心捕获并记录，但 Simurail、Sable 或其依赖在模组加载阶段崩溃仍可能阻止整服
启动；当前不能承诺任意 Alpha 升级都可安全加载存档。上述恢复路径尚未经过进入
世界、移动列车和专服重启验收。

## 必测场景

每个物理列车候选都必须通过以下场景：

1. 通达铁路直线、曲线、坡道、桥梁和隧道；
2. 生成器在列车前方补出新区段；
3. 列车跨越区块加载边界；
4. 全员离线后重启专用服务器；
5. 单人世界打开和关闭联机；
6. 司机断线后由其他玩家接管；
7. 道岔、车站装配和车厢脱钩；
8. 断轨、翻覆和实体碰撞后的恢复；
9. 连续运行至少 100 个游戏日的存档耐久测试。

当前九项均未完成；现有运行证据仅为专服发现完整模组列表并在
`eula=false` 处合法停止。

未通过恢复测试的物理效果只能作为可选困难模式，不能成为默认的永久失败条件。
