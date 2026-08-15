# 客户端与单人世界开发安装

开发客户端使用 Prism Launcher 实例格式。脚本固定 Minecraft 1.21.1、
NeoForge 21.1.244、Packwiz Installer 0.5.14 和 Create Simurail 提交
`e68481dcf56de6a020e42880792526c77b017060`。

同一个实例可以：

- 新建单人世界；
- 将单人世界开放到局域网；
- 加入安装了相同版本的专用服务器。

## 前置条件

- Java 21 JDK 位于 `PATH`；脚本需要它构建项目核心和运行 Packwiz；
- 已安装 Prism Launcher，但不要求脚本访问其账户数据；
- `curl`、`sha256sum`、`git` 和常见 POSIX 命令；
- 可以访问 Modrinth、CurseForge、GitHub、NeoForge 和 Minecraft 的官方服务。

安装器是 Bash 脚本，面向 Linux、WSL 或带有 GNU `sha256sum` 的 Git Bash。
Windows 原生 PowerShell 尚未提供等价入口；使用 WSL/Git Bash 时，应把目标
指向 Windows 上 Prism 的真实实例目录。

Create Simurail 还没有正式 Release。脚本优先通过已登录的 GitHub CLI 获取固定
Actions artifact；artifact 不可用时，会从同一固定提交的源码归档构建。上游
artifact 必须匹配 JAR SHA-256
`d85ee304d972397807f801aae73a167163729628e382b26db0fcc0c52834e602`。
本地源码构建允许 ZIP 时间戳/顺序不同，但解压后的路径和内容必须匹配
`aa30b9b0a4e27ba90aedb6e4ff171a94e88c01c7f0c95ef2d57bd9a253f3d63b`，
否则安装中止。

## 安装

先在 Prism Launcher 设置中找到实例目录。给脚本一个该目录下尚不存在或为空的
子目录，例如：

```bash
./scripts/install-dev-client.sh \
  /path/to/PrismLauncher/instances/lasttrain-dev
```

脚本会创建：

- `instance.cfg` 与 `mmc-pack.json`，供 Prism 固定游戏和加载器版本；
- `.minecraft/`，其中包含 Packwiz `client` side 的模组、配置与脚本；
- 本地构建并校验记录过的 `lasttrain` 核心 JAR；
- 固定提交并经过 SHA-256 校验的 Simurail JAR；
- `.lasttrain-install-state`，记录 Packwiz 索引、核心 JAR、Simurail 和源码状态。

安装结束后重启或刷新 Prism Launcher，检查实例版本为 Minecraft 1.21.1 与
NeoForge 21.1.244，并在实例设置中选择 Java 21，再由 Prism 启动。脚本只检查
当前终端的 Java，不会改写 Prism 的启动器级 Java 设置。Microsoft/Minecraft
登录、游戏下载和任何服务条款都由 Prism 或对应服务显式处理；脚本不会创建
账户、启动游戏、自动同意 EULA 或代表用户接受外部条款。

## 安全更新与存档迁移

脚本只接受空目录，或带有正确 `.lasttrain-managed-client` 标记的既有实例。
它拒绝覆盖普通 Prism 实例、Packwiz 源目录、Git 元数据和关键符号链接。
管理标记只表示目录归属，不表示安装已经完成；只有成功生成
`.lasttrain-install-state` 且不存在 `.lasttrain-install-in-progress` 时，
整条安装流程才算完成。中途失败会保留进行中标记；应修复错误并重新运行同一
命令。

再次运行脚本会保留 `instance.cfg` 中的 Prism 用户设置，重新应用固定的
Minecraft/NeoForge 组件和 Packwiz 清单，并将旧的自研核心与 Simurail 放入实例内的
`.lasttrain-installer-cache/replaced-mods/`。手工加入的其他模组不属于此脚本
的可复现清单，可能造成客户端与服务器不一致。

请不要直接把已有实例改名来伪造管理标记。迁移单人存档时：

1. 完整备份旧实例的 `.minecraft/saves/`；
2. 安装一个全新的受管理实例并至少成功进入主菜单；
3. 退出游戏后，仅复制所需的世界目录到新实例；
4. 保留原备份，直到世界完成创建、保存、退出和重进测试。

脚本不会自动迁移或删除世界。

## 安装器自测

以下测试使用临时目录和模拟的 Java、Packwiz、Gradle、下载器，不会下载模组或
启动真实 Gradle 构建：

```bash
./scripts/tests/install-dev-client-test.sh
```
