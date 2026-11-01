# RadminLAN for Minecraft 1.21.11

This is an isolated compatibility port. It has its own Gradle configuration and source tree so it can use the Java 21 and Fabric APIs required by Minecraft 1.21.11 without affecting the 26.x builds.

Build from the repository root with JDK 21:

```powershell
./gradlew.bat -p versions/1.21.11 build
```
