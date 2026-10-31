# RadminLAN

RadminLAN 是一个面向 Fabric 26.3 的客户端模组原型，用于在 Minecraft 内管理 Radmin LAN/VPN 网络，并把局域网房间、状态、延迟和屏蔽规则集中到一个界面。

## 当前实现

- 多人游戏界面提供可配置位置的 RadminLAN 入口按钮。
- 管理界面提供 Networks、Peers、LAN Rooms、Blocked 四个页签。
- LAN 多播发现使用独立线程，房间按 IP+端口去重并缓存。
- 屏蔽规则支持 IP、IP+端口和 MOTD，默认使用 IP+端口。
- 屏蔽后的房间从可见房间列表移入独立的 Blocked 页签，可恢复显示。
- Radmin CLI 适配层不会阻塞渲染线程；未检测到客户端时界面仍然可用。
- Mod Menu 集成 YACL 配置页，配置文件保存到 `config/radminlan.json`。

## Radmin 客户端适配

Radmin LAN/VPN 没有稳定的公开 Java API，因此模组通过可配置的命令行程序路径调用 `network list/create/join/leave`。不同版本客户端的 CLI 输出可能不同，解析器保持保守：无法确认的数据会显示为不可用，不会阻塞游戏或写入密码。

首次运行后可以在 `config/radminlan.json` 中设置：

```json
{
  "radminExecutable": "C:/Program Files/Radmin VPN/Radmin VPN CLI.exe",
  "enableCli": true,
  "queryTimeoutMs": 1000,
  "defaultBlockType": "IP_PORT"
}
```

不要把 Radmin 密码提交到仓库；创建/加入网络时只在内存中传递给 CLI。

## 构建

需要 JDK 25 和 Gradle 8.10+。使用仓库中的 Fabric Loom 配置执行：

```text
gradle build
```

Fabric/Yarn、YACL 和 Mod Menu 的版本集中在 `gradle.properties` 与 `build.gradle`，后续移植其它 Minecraft 版本时只需要替换这些版本和少量 Mixin 目标。

## 后续工作

1. 按目标 Radmin 客户端版本补齐 CLI 输出解析和 Windows 服务探测。
2. 在多人列表行中注入 MOTD、封面、人数、延迟和红色删除按钮的原生布局。
3. 用 Blockbench 制作 Radmin 像素图标，并替换入口按钮中的占位文本。
4. 为不同 Minecraft 版本建立独立 mappings/source set，并补充游戏内手动测试。

