# RadminLAN

RadminLAN 是一个面向 Fabric 26.3 的客户端模组，用于在 Minecraft 内查看 Radmin LAN/VPN 状态、网络和用户，并集中管理局域网房间。

当前版本：`0.1.2`

作者：flexfox0424（GitHub: block-fox）

## 已实现

- 在多人游戏界面添加可配置位置的 RadminLAN 入口。
- 管理界面包含网络、用户、局域网房间和已屏蔽四个页签。
- 通过 Windows Radmin 控制管道读取服务状态、网络、用户和延迟信息；配置了 CLI 路径时可回退到 CLI。
- 使用独立线程执行 Radmin 查询和 LAN 多播发现，不阻塞 Minecraft 渲染线程。
- 房间显示封面、完整 MOTD、地址、玩家数、延迟和状态，列表支持滚动。
- 房间支持按 IP、IP+端口或 MOTD 记忆屏蔽，屏蔽后移动到独立页签，可恢复显示。
- Mod Menu 提供配置入口，配置保存到 `config/radminlan.json`。

## 版本目录

- `versions/26.3/`：Fabric 26.3 构建配置和输出包。
- `versions/26.2/`：Fabric 26.2 构建配置和输出包。
- `versions/1.21.11/`：使用独立源码和 Gradle 配置的 Fabric 1.21.11 移植工程。

26.2 和 26.3 共用代码，各自配置和打包产物分开放置。1.21.11 的 Minecraft GUI API 不同，因此使用独立源码。每个构建包文件名都会标注兼容版本，例如 `radminlan-0.1.2-26.3.jar`、`radminlan-0.1.2-26.2.jar` 和 `radminlan-0.1.2-1.21.11.jar`。

## 调试构建

使用 JDK 25 构建 Fabric 26.3 并整理到 `versions/26.3/build/`：

```text
powershell -ExecutionPolicy Bypass -File scripts/build-26.3.ps1
gradlew.bat runClient --offline
```

本机调试默认使用 `libs/` 中的离线运行库。没有这些缓存时可使用公开 Maven 依赖构建：

```text
gradlew.bat build -Puse_local_runtime=false
```

Fabric 26.2 使用独立配置，构建包整理到 `versions/26.2/build/`：

```text
powershell -ExecutionPolicy Bypass -File scripts/build-26.2.ps1
```

Fabric 1.21.11 工程使用 JDK 21：

```text
gradlew.bat -p versions/1.21.11 build
```

Fabric 26.2 和 26.3 构建使用 JDK 25。

## 配置示例

```json
{
  "radminExecutable": "C:/Program Files/Radmin VPN/Radmin VPN CLI.exe",
  "pipeName": "\\\\.\\pipe\\RadminVpnGuiChannel",
  "enableCli": true,
  "queryTimeoutMs": 1000,
  "defaultBlockType": "IP_PORT"
}
```

Radmin 密码只在运行时传递给后端，不应提交到仓库。

## 参考项目

`RvpnKit` 只用于协议和本地客户端行为参考；本项目的 Minecraft 集成、UI、房间缓存和配置代码均独立实现。
