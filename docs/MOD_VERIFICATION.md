# 模组事实核查表

> 目的：把 COMPATIBILITY.md 与 CONTENT_SUPPLEMENT.md 中所有涉及第三方模组的
> 断言逐一核对，区分"源码确认 / 官方页面确认 / 待实测"。
> 核查日期：2026-08-01。

## 核查结论总览

| 模组 | 断言 | 证据 | 结论 |
| --- | --- | --- | --- |
| 失落城市 | datapack 预定义城市球（predefinedspheres）：dimension/chunkx/chunkz/centerx/centerz/radius | 源码 `PredefinedSphereRE.java`（Codec）；`CitySphere.getCitySphere()` 优先匹配预定义球；`CITYSPHERE_ONLY_PREDEFINED` 仅生成预定义城市 | ✅ 源码确认 |
| 失落城市 | 1.21.1 NeoForge 分支存在 | GitHub 分支 `1.21_neo`、`1.21.11_neo` | ✅ 确认 |
| 失落城市 | 城市箱子战利品表 `lostcitychest` / `raildungeonchest` | 仓库 `data/lostcities/loot_table/chests/` 下对应文件 | ✅ 确认 |
| 失落城市 | 城市区块有 `cityLevel`（城市等级） | 源码 `LostChunkCharacteristics.cityLevel` | ✅ 源码确认 |
| 失落城市 | 城市内部铁路为原版铁轨/矿车车站 | 仓库 `parts/rails_*.json`、`station_*.json` 为原版轨道部件 | ✅ 确认 |
| 通达铁路 | 站点规划：低差异序列选点→海洋过滤→三角网→出口匹配 | 源码 `StationPlanner.java` | ✅ 源码确认 |
| 通达铁路 | 车站按节点度数选 2/4 出口 | 源码 `StationPlanner.generateStation()` | ✅ 源码确认 |
| 通达铁路 | 配置：`areaGenerationRailwayProbability`（默认 0.4）、track spawner 系列 | 源码 `Config.java` | ✅ 源码确认 |
| 通达铁路 | 桥/隧道/车站模板 = JSON 元数据 + NBT 结构 | 仓库 `data/tongdarailway/railway_structure/` 与 `structure/` | ✅ 确认 |
| 通达铁路 | 无"外部指定站点"API | 源码检索仅见 `ITrackPreGenExtension`（铺轨扩展） | ✅ 确认 |
| 通达铁路 | GPL-3.0 | 仓库 LICENSE 文件 | ✅ 确认 |
| 汽鸣铁道 | 官方团队跳过 1.21.1，非官方移植版可用 | Porters-of-Railways 仓库 README；mcmod 页面"即将支持" | ✅ 确认 |
| 汽鸣铁道 | 连挂器：1 铁板 + 1 红石粉 + 1 列车机壳；直行轨道；成对钩位；供电连接/断开 | mcmod 物品页 670086；Modrinth 描述 | ✅ 确认（配方以实测为准） |
| 汽鸣铁道 | 臂板信号机、转辙器、指挥机器人、显示链接器 | mcmod 模组页 8230；官方仓库 README（semaphores/conductors） | ✅ 确认 |
| 汽鸣铁道 | 特殊轨道：不同轨距、幻缈/隐身轨道、倒挂单轨 | mcmod 模组页与物品页 | ✅ 确认 |
| 汽鸣铁道 | **提供蒸汽机车/锅炉/燃料机制** | 官方 README 与功能清单均无蒸汽锅炉 | ❌ 已纠正：不存在，需自研或另选模组 |
| 汽鸣铁道 | 多样转向架、适配轨距、菜单支持更多转向架模组 | mcmod 模组页 8230 | ✅ 确认 |
| 汽鸣铁道 | LGPL 许可 | 官方与移植版仓库 LICENSE | ✅ 确认 |
| Create | 列车 = 装配体；五步装配流程；原生无解挂 | 官方 Wiki/教程（cnblogs 1.21 详解）；GitHub issue #7960/#4200 | ✅ 确认 |
| Create | 方块无通用耐久（铁砧 damage 为 blockstate 特例） | 原版机制 | ✅ 确认 |
| Blocks & Bogies | 80+ 转向架样式、选择界面、Create 6.0.8+、1.21.1 NeoForge、与 SnR 菜单互通 | Modrinth/CurseForge/mcmod 21205；SnR 移植版更新说明 | ✅ 确认 |
| Extended-Bogeys | 旧版可选配置速度/加速度/转弯半径（默认关闭） | GitHub Rabbitminers/Extended-Bogeys | ✅ 确认（已过时） |
| Create Aeronautics | 1.3.0 已发布 1.21.1 NeoForge | CurseForge 文件页（2026-06-13）；Modrinth | ✅ 确认 |
| Create Aeronautics | **提供铁路/燃料机制** | Modrinth/mcmod 官方页仅描述"模拟/航空/越野"三板块，无铁路与燃料记载 | ❌ 未证实（作者提供信息，待实测） |
| Create Train Physics Reloaded | Create 列车燃料（require fuel）、滚动阻力、质量、牵引力、弯道限速 | Mado Hosting 模组页面描述 | ✅ 页面确认（待实测评估） |
| Loconautics | 尚未正式发布 | 检索未见正式发布记录 | ⚠️ 维持原判断，待复核 |
| Create Simurail / Ferronautics | 实验/过渡状态 | 原 COMPATIBILITY.md 记录 | ⚠️ 待复核 |

## 文档更正记录（2026-08-01）

1. 删除"汽鸣铁道提供蒸汽机车/锅炉/燃料压力"断言；燃料来源按作者经验在
   物理化列车（航空学铁路生态）一侧，公开文档未见记载，待实测；
2. 更正"Create 无转辙器"：原生 Create 确无，但汽鸣铁道提供转辙器设备；
3. 补充连挂器配方细节（1 铁板 + 1 红石粉 + 1 列车机壳）；
4. CONTENT_SUPPLEMENT §6.6 由"属性升级/工时"改写为"物理改造"模型；
5. 明确 Create Aeronautics 1.3.0 为当前可用的 1.21.1 物理方案之一，
   铁轨场景需实测。

## 待实测清单

- 连挂器在 Aeronautics/Sable 物理列车上的连接/断开；
- 物理化列车（航空学铁路生态）的燃料消耗机制确认；
- Create Train Physics Reloaded 的 require fuel 选项与本项目物理后端的
  兼容性评估；
- 独立车厢（无驾驶台）能否作为"列车"参与连挂；
- 汽鸣铁道转辙器与信号机在自动站场中的联动；
- Create 原生列车是否零燃料消耗（自研燃料机制前需先确认）；
- 通达铁路车站模板与 lasttrain 站场模板的接口对齐；
- 失落城市预定义球在 1.21.11_neo 分支上的实际 JSON 路径与字段兼容。
