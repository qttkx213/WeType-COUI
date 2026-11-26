# 构建与开发

## 环境

- JDK 21。将 `JAVA_HOME` 指向 JDK 目录。
- Android SDK。将 `ANDROID_HOME` 指向 SDK 目录，并安装 `platforms;android-37.0` 和 `build-tools;36.0.0`。
- 第一次构建需要联网下载 Gradle 和项目依赖。构建模块只需本仓库的源码与公开依赖。
- COUI-Kit 固定为 Maven Central 的 `io.github.itoseo:coui:0.0.1-alpha12`。该版本要求编译 SDK 37、AGP 9.1.0 或以上。项目使用 AGP 9.2.1。 [对应版本的兼容说明](https://github.com/ItosEO/COUI-Kit/blob/v0.0.1-alpha12/docs/guide/compatibility.md)

## 构建

在仓库根目录运行。Windows 使用 PowerShell 7。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

macOS 或 Linux 使用仓库自带的 Gradle Wrapper。

```sh
sh ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 也可运行 `build.ps1`。该脚本默认使用仓库旁边的 `AndroidBuild` 目录，里面放 `android-sdk` 和 `jdk/jdk-21*`。工具另有位置时使用 `-ToolsRoot` 指定。

```powershell
./build.ps1 -ToolsRoot 'D:\AndroidBuild'
```

输出位于 `app/build/outputs/apk/debug/app-debug.apk`。编译版本由 `app/build.gradle.kts` 指定。Gradle 使用本机构建环境的调试证书签名。覆盖安装要核对包名、签名和版本码。

## 代码入口

| 文件 | 作用 |
| --- | --- |
| `WeTypeModule.java` | LSPosed 入口、目标版本检查、输入法窗口生命周期和旧版 COUI Demo 兼容 |
| `KeyboardStyle.java`、`KeyboardBackdrop.java`、`KeyboardPanelLight.kt` | 材质挂载、系统底板模糊和轮廓光 |
| `IconButtonMotion.kt`、`DrawHooks.java`、`KeyboardLayers.java` | 触摸反馈、自绘键接入和拖起组件绘制 |
| `CouiBackdropBlur.java`、`CandidateGridBackdrop.java` | 同窗采样和展开候选删除键模糊 |
| `CouiSegmentButton.kt`、`SegmentedTabs.kt`、`SymbolCategories.kt`、`KeyboardControls.kt` | 分段、滑选、表情和数字符号 |
| `ExpandedCandidates.kt`、`CandidateSyllables.kt`、`CandidateStrokes.kt` | 候选键、音节分页和首笔画筛选 |
| `ClipboardPreviewControls.java`、`ToolbarPager.kt`、`PageMotion.java` | 复制预览、功能卡分页和工具面板转场 |

源码位于 `app/src/main/java/local/wetype/couiglass/`。模块按微信输入法 `59201` 的内部布局接入。适配新版本时，先核对布局、按键类和资源 ID，再验证输入与页面切换。

## 验收

- 在 ColorOS 17 用目标输入法测深浅色、按键回弹、候选左栏、复制预览四向拖动、符号栏触边和工具页进退。检查主键盘的页面直接切换。
- 临时关闭系统模糊，检查键盘底色、圆角和文字对比度。关闭系统动画时，检查一步切换和手指跟手。检查完成后恢复设备设置。
- 查模块加载时，在 LSPosed 日志中搜索 `WeTypeCOUI`。观察版本匹配、布局就绪和材质初始化。
- 核对 APK 中的 LSPosed 入口与许可文本。打包时会收缩未使用的资源，上游资源适用各自的权利声明。 [资源来源](https://github.com/ItosEO/COUI-Kit/blob/v0.0.1-alpha12/coui/RESOURCE_SOURCES.md)
