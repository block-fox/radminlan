# RadminLAN

RadminLAN 是一个 Fabric 客户端模组，目前支持 Minecraft 1.21.11、26.2、26.3，后续可能移植（neo）forge。用于在 Minecraft 内查看 Radmin LAN/VPN 状态、网络和用户，并集中管理局域网房间。

当前版本：`0.2.0`，完整更新见 [CHANGELOG.md](CHANGELOG.md)。

## 已实现

- 在多人游戏界面添加可配置位置的 RadminLAN 入口。
- 管理界面包含网络、用户、已屏蔽和设置四个页签，局域网房间整合在原版多人游戏列表中。
- 网络和用户支持八种查看方式、排序、过滤和多选；查看指定网络用户时自动应用网络过滤。
- 顶部可直接修改用户名和切换 Radmin 电源，关闭电源前需确认。
- 通过 Windows Radmin 控制管道读取服务状态、网络、用户和延迟信息；配置了 CLI 路径时可回退到 CLI。
- 使用独立线程执行 Radmin 查询和 LAN 多播发现，不阻塞 Minecraft 渲染线程。
- 持续订阅读取实际延迟，使用信号图标显示连接状态，悬停图标查看简洁信息。
- 可切换自动刷新并调整 1–60 秒刷新间隔，设置自动保存。
- 房间显示封面、完整 MOTD、地址、玩家数、延迟和状态，列表支持滚动。
- 房间支持按 IP、IP+端口或 MOTD 记忆屏蔽，屏蔽后移动到独立页签，可恢复显示。
- Mod Menu 提供配置入口，配置保存到 `config/radminlan.json`。
- 列表、属性页和设置支持小窗口滚动裁剪；Radmin 房间标题显示为“Radmin局域网世界”。

## 版本目录

- `versions/26.3/`：Fabric 26.3 构建配置和输出包。
- `versions/26.2/`：Fabric 26.2 构建配置和输出包。
- `versions/1.21.11/`：使用独立源码和 Gradle 配置的 Fabric 1.21.11 移植工程。

26.2 和 26.3 共用代码，各自配置和打包产物分开放置。1.21.11 的 Minecraft GUI API 不同，因此使用独立源码。每个构建包文件名都会标注兼容版本：`radminlan-0.2.0-26.3.jar`、`radminlan-0.2.0-26.2.jar` 和 `radminlan-0.2.0-1.21.11.jar`。请安装与游戏版本对应的包。

## 调试构建

使用 JDK 25 构建 Fabric 26.3 并整理到 `versions/26.3/build/`：

```text
powershell -ExecutionPolicy Bypass -File scripts/build-26.3.ps1
powershell -ExecutionPolicy Bypass -File scripts/run-26.3.ps1
```

本机调试默认使用 `libs/` 中的离线运行库。没有这些缓存时可使用公开 Maven 依赖构建：

```text
gradlew.bat build -Puse_local_runtime=false
```

Fabric 26.2 使用独立配置，构建包整理到 `versions/26.2/build/`：

```text
powershell -ExecutionPolicy Bypass -File scripts/build-26.2.ps1
```

Fabric 1.21.11 工程使用 JDK 21，输出整理到 `versions/1.21.11/build/`：

```text
powershell -ExecutionPolicy Bypass -File scripts/build-1.21.11.ps1
powershell -ExecutionPolicy Bypass -File scripts/run-1.21.11.ps1
```

Fabric 26.2 和 26.3 构建使用 JDK 25。

这些脚本支持 `-JavaHome` 指定 JDK 路径，默认使用本地缓存；首次下载依赖可加 `-Online`。构建脚本通过参数选择版本，不改写根目录配置。切换版本或构建前请退出开发客户端。

回归检查覆盖鼠标编号、配置迁移、查看方式与过滤、128 组列表布局、在线状态与延迟协议、Radmin 房间标题匹配。使用对应 JDK 执行：

```text
gradlew.bat -I tests/regression.init.gradle uiRegressionCheck --offline --no-configuration-cache
gradlew.bat -I tests/regression.init.gradle uiRegressionCheck -Pminecraft_version=26.2 -Pminecraft_dependency=~26.2 -Pfabric_version=0.161.0+26.2 -Pmodmenu_version=20.0.3 -Puse_local_runtime=false --offline --no-configuration-cache
gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle uiRegressionCheck --offline --no-configuration-cache
```

安装并运行 Windows Radmin VPN 后，可将任务名换成 `nativeTelemetrySmoke`，验证只读订阅和关闭清理；该检查仅输出汇总数量。

## 配置示例

```json
{
  "radminExecutable": "C:/Program Files/Radmin VPN/Radmin VPN CLI.exe",
  "pipeName": "\\\\.\\pipe\\RadminVpnGuiChannel",
  "enableCli": true,
  "queryTimeoutMs": 1000,
  "defaultBlockType": "IP_PORT",
  "autoRefresh": true,
  "refreshIntervalSeconds": 2,
  "networkViewMode": "LIST",
  "peerViewMode": "LIST"
}
```

## 参考项目

只用官方客户端作为行为参考；本项目的 Minecraft 集成、UI、房间缓存和配置代码均独立实现。
