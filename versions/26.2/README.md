# RadminLAN for Minecraft 26.2

当前版本：`0.2.0`。包含与 26.3 一致的界面、设置标签、查看方式、排序过滤、信号提示及房间属性优化。

- 统一适配 26.2 与 26.3 的鼠标按钮编号，修复网络、用户和 LAN 房间的右键操作。
- 修复属性页左键复制以及管理页、公共网络页、房间详情的滚动条拖动。
- 为网络、用户属性页补充空对象、缺失网络列表和字段的保护。
- 打开属性页时清理旧的上下文菜单控件，避免返回后残留菜单按钮。
- 构建时使用正确的 Mojang 运行库，并将该构建包的兼容范围限定在 26.2 系列。

测试时使用 JDK 25，在仓库根目录直接启动开发客户端：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-26.2.ps1
```

可以通过 `-JavaHome` 指定 JDK 25 的安装目录；首次下载依赖或资源时可加 `-Online`。
启动脚本直接使用 26.2 配置，不改动根目录的默认版本配置，也不打包 JAR。

使用 JDK 25 构建发布包：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-26.2.ps1
```

构建输出：`versions/26.2/build/radminlan-0.2.0-26.2.jar`。

实机回归重点：网络和用户的右键菜单、查看属性、复制字段、关闭属性页后再次右键，以及 LAN 房间右键详情和普通双击加入。

回归检查命令见仓库根目录 README；如遇异常，请保留实例的 `crash-reports` 或 `logs/latest.log`。
