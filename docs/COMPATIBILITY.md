# 兼容性基线

状态日期：2026-08-01（含模组事实核查，见 MOD_VERIFICATION.md）。

## 目标平台

| 组件 | 目标 |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge 21.1.x |
| Java | 21 |
| 机械动力 | Create 6.0.x |
| 汽鸣铁道 | Create: Steam 'n' Rails 1.21.1 NeoForge 移植版
  （[Porters-of-Railways](https://github.com/Porters-of-Railways/Railway-1.21.1)，
  官方跳过 1.21.1，当前为非官方移植版，LGPL），版本随 Create 锁定 |
| 物理引擎 | Sable，版本随最终载具实现锁定 |

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

## 汽鸣铁道（Create: Steam 'n' Rails）

[汽鸣铁道](https://www.mcmod.cn/class/6619.html) 是 Create 的铁路扩展。
**官方团队跳过 1.21.1 版本**，本项目使用非官方移植版
（[Porters-of-Railways](https://github.com/Porters-of-Railways/Railway-1.21.1)，
LGPL，随最新版 Create 锁定；官方移植完成后需重新评估切换）。它补齐原生
Create 缺失的列车运营能力：

- **列车连挂器**：供电连接/断开两列独立列车，是"解挂、连挂、换车头"
  的物理基础（原生 Create 无此能力；配方：1 铁板 + 1 红石粉 + 1 列车机壳）；
- **臂板信号机 + 显示链接器**：为任务通行锁与红绿灯信息提供真实信号方块
  和状态显示；
- **转辙器设备**：管理岔道切换（原生 Create 无此方块）；
- **指挥机器人**：操控红石元件、携带工具箱、驾驶列车；
- **特殊轨道**：不同轨距、幻缈/隐身轨道、倒挂单轨，扩展线路生成的地形
  适应性。

**核查纠正（2026-08-01）**：汽鸣铁道不提供"蒸汽机车烧燃料/锅炉压力"
机制，Create 原生列车无需燃料；燃料/锅炉压力如需实现，属于 lasttrain
自研逻辑或另选模组，不在汽鸣铁道依赖范围内。

兼容性要求：

- 连挂器运行在 Create 轨道层，与物理后端（Sable/Aeronautics）的兼容性
  必须实测并写入 VehicleBackend 能力标志（`COUPLE/DECOUPLE`）；
- 汽鸣铁道只支持最新版 Create，升级 Create 前必须先做连挂器与信号机的
  回归测试；
- 与 Create: Blocks & Bogies 等转向架扩展的互操作以移植版发布说明为准。

## 物理列车候选

### Loconautics

[Loconautics](https://www.curseforge.com/minecraft/mc-mods/loconautics)
的设计目标最符合本项目：Create 列车以 Sable 物理子世界存在，具有质量、
碰撞和实体车厢。但官方页面目前仍标注尚未发布，不能作为首个可构建版本的
硬依赖。

### Create Simurail

[Create Simurail](https://github.com/Crystaelix/Create-Simurail)
目前属于实验开发状态，没有正式 GitHub Release。它可以进入兼容性实验分支，
但不能直接承担长期存档承诺。

### Ferronautics

[Ferronautics](https://www.curseforge.com/minecraft/mc-mods/ferronautics)
可用于早期概念验证，但其定位是过渡性的粗糙 Beta，不作为正式载具后端。

### Create Aeronautics（物理装配体基线）

[Create Aeronautics](https://modrinth.com/mod/create-aeronautics)
1.3.0 已发布 1.21.1 NeoForge 版本（2026-06-13），提供基于 Sable 的物理
装配体（飞艇、飞机、车辆）。列车/铁轨场景属于其生态扩展（如 SG Tracks
提供驱动轮/支撑轮），**铁轨上的表现需实测后决定是否作为正式载具后端**，
不与任务与存档承诺绑定（对齐 CONTENT_SUPPLEMENT §6.2 的蓝图部署模型）。

## 载具适配策略

核心玩法只依赖项目自有的 `TrainBackend` 能力边界：

- 查找或生成默认列车；
- 查询列车位置、速度和质量；
- 判断玩家是否在列车上；
- 锁定、解锁和恢复列车；
- 取得车厢列表；
- 把列车重定位到有效轨道；
- 报告后端是否支持真实碰撞、脱轨和车厢损坏。

初期提供不依赖第三方物理列车 API 的安全后端。实验后端通过反射或独立兼容
模块接入，最终只有通过兼容性门槛的后端才会进入发布整合包。

载具能力以标志位协商：`COUPLE/DECOUPLE`（连挂/解挂，由汽鸣铁道提供）、
`STEAM`（蒸汽锅炉/燃料，由汽鸣铁道提供）、`PHYSICAL_COLLISION`（真实碰撞、
脱轨、车厢损坏）、`BOARDING`（实体随车移动，物理后端支持时提供）等。
缺少能力时核心层走降级路径，不因某个标志缺失而卡死战役。

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
10. 汽鸣铁道连挂器在普通 Create 与物理后端下的连接/断开与换车头；
11. 信号机与任务通行锁联动，任务完成前后信号状态正确。

未通过恢复测试的物理效果只能作为可选困难模式，不能成为默认的永久失败条件。
