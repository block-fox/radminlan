# RadminLAN for Minecraft 1.21.11

This is an isolated compatibility port. It has its own Gradle configuration and source tree so it can use the Java 21 and Fabric APIs required by Minecraft 1.21.11 without affecting the 26.x builds.

Version: **0.2.0**. Includes the same management UI, settings tab, view/sort/filter options, signal tooltips and room properties as the 26.x releases. The Java 21 port uses JNA for Windows pipe access.

Build and run from the repository root with JDK 21:

```powershell
./scripts/build-1.21.11.ps1
./scripts/run-1.21.11.ps1
```

Use `-JavaHome` to select a JDK or `-Online` to download missing dependencies. The release artifact is `build/radminlan-0.2.0-1.21.11.jar` in this version directory.
