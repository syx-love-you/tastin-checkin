# Android 模块开发说明

本目录是 Android 应用源码，面向开发者和贡献者。用户安装与使用说明见 [release/使用说明.md](../release/使用说明.md)。

## 环境

- Android 7.0+（`minSdk 24`）
- `compileSdk 34`
- Java 11
- Gradle 构建路径必须使用纯英文目录

## 构建与验证

```powershell
robocopy ".\android" "C:\build-tastin\android" /E
cd C:\build-tastin\android
gradle.bat assembleDebug
gradle.bat lintDebug
```

正式签名 APK 不会提交到仓库。

## 本地签名配置

`android/local.properties` 只保留在本机，不提交到仓库。可配置：

```properties
sdk.dir=<LOCAL_SDK_PATH>
TASTIN_KEYSTORE=<LOCAL_KEYSTORE_PATH>
TASTIN_STORE_PASSWORD=<LOCAL_SECRET>
TASTIN_KEY_ALIAS=<LOCAL_ALIAS>
TASTIN_KEY_PASSWORD=<LOCAL_SECRET>
```
