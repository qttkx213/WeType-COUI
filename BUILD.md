# 构建与开发

## 环境

- JDK 21。将 `JAVA_HOME` 指向 JDK 目录。
- Android SDK。将 `ANDROID_HOME` 指向 SDK 目录，并安装 `platforms;android-37.0` 和 `build-tools;36.0.0`。
- 第一次构建需要联网下载 Gradle 和项目依赖。构建模块只需本仓库的源码与公开依赖。
- COUI-Kit 固定为 Maven Central 的 `io.github.itoseo:coui:0.0.1-alpha12`。该版本声明最低编译 SDK 37、最低 AGP 9.1.0。项目使用 AGP 9.2.1。 [对应版本的兼容说明](https://github.com/ItosEO/COUI-Kit/blob/v0.0.1-alpha12/docs/guide/compatibility.md)

## 构建

在仓库根目录运行。Windows 使用 PowerShell。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

macOS 或 Linux 使用仓库自带的 Gradle Wrapper。

```sh
sh ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 也可在 PowerShell 7 中运行 `build.ps1`。该脚本默认使用仓库旁边的 `AndroidBuild` 目录，里面放 `android-sdk` 和 `jdk/jdk-21*`。工具另有位置时使用 `-ToolsRoot` 指定。

```powershell
./build.ps1 -ToolsRoot 'D:\AndroidBuild'
```

输出文件为 `app/build/outputs/apk/debug/app-debug.apk`。Gradle 使用本机构建环境的调试证书签名。覆盖安装已有模块时，需要用相同的证书签名。

模块打包时会收缩未使用的资源。发版时检查 APK 里的资源、LSPosed 入口和许可文本。上游资源适用各自的权利声明。 [资源来源](https://github.com/ItosEO/COUI-Kit/blob/v0.0.1-alpha12/coui/RESOURCE_SOURCES.md)

## 代码入口

| 文件 | 作用 |
| --- | --- |
| `WeTypeModule.java` | LSPosed 入口、目标版本检查、输入法窗口生命周期 |
| `KeyboardStyle.java`、`KeyboardBackdrop.java`、`KeyboardLayers.java` | 视图材质、底板模糊与同窗绘制 |
| `IconButtonMotion.kt`、`PageMotion.java` | 跟手反馈与功能面板转场 |
| `SegmentedTabs.kt`、`ToolbarPager.kt`、`KeyboardControls.kt` | 分段选择、功能分页、表情和数字符号控件 |

源码位于 `app/src/main/java/local/wetype/couiglass/`。模块按微信输入法 `59201` 的内部布局接入。适配新版本时，先核对布局、按键类和资源 ID，再测试输入与页面切换。

## 验收

使用 [README 中的兼容组合](README.md#安装)验证深浅色键盘、按键回弹、中英文与数字切换、表情拖选、功能面板分页、候选展开和剪贴板滚动。窗口模糊不可用时，检查键盘底色与文字对比度。排查模块加载时，在 LSPosed 日志中搜索标签 `WeTypeCOUI`。
