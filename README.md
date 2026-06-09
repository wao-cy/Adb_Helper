# ADB Helper

基于 Android 设备直接运行 ADB 命令的调试工具，支持脚本管理、应用管理、文件传输等功能。应用内置 ADB 二进制，无需 Root 权限，可通过 USB 或无线连接管理 Android 设备。

## 功能特性

### 核心功能
- **ADB 服务器管理** — 启动/停止 ADB 服务器，端口状态检测
- **设备连接** — USB 和无线 (TCP/IP) 连接，支持局域网设备扫描（并发 50 线程扫描）
- **配对支持** — Android 11+ 无线调试配对（支持输入配对码）
- **Root 检测** — 自动检测设备 Root 状态
- **交互式 Shell** — 实时流式 Shell 终端，支持 ANSI 转义序列解析

### 脚本管理
- **脚本编辑器** — 可视化编辑命令列表，支持分类管理
- **脚本引擎** — 顺序执行命令，带超时控制、错误处理、条件执行
- **变量支持** — 脚本中使用 `${变量名}` 或 `$变量名` 形式的变量
- **交互命令** — `input`（用户输入）、`confirm`（二次确认）、`delay`（延时）
- **变量操作** — `set VAR=value` 赋值、`set /p VAR=prompt` 交互输入、`set /a VAR=expr` 算术运算、`echo` 输出
- **条件执行** — `exists:路径`、`not_exists:路径`、`package:包名` 条件判断
- **错误控制** — `!` 前缀命令在失败时终止脚本；其他命令忽略错误继续执行
- **预置模板** — 内置「设备信息采集」「交互式应用清理」「变量操作演示」模板
- **流式输出** — 执行过程中实时显示命令输出（通过 SharedFlow + Ack 机制保证顺序）
- **执行历史** — Room 数据库持久化脚本定义与执行记录

### 应用管理
- **包列表** — 查询所有已安装应用包
- **应用操作** — 启动/停止/清除数据/卸载/禁用/启用
- **APK 传输** — 从本地选择 APK 推送到设备安装，带传输进度回调
- **多策略降级** — 命令失败时自动尝试 `--user 0` 或 `su -c` 等备选方案

### 文件传输
- **Push/Pull** — 本地与设备间的文件传输
- **进度回调** — 传输过程中实时回调百分比、速度、已传输字节数
- **自动建目录** — 本地目标目录不存在时自动创建

### 设备信息
- 设备属性（型号、品牌、Android 版本等）
- 电池状态（`dumpsys battery`）
- 磁盘使用情况（`df -h`）
- 屏幕信息（`wm size` / `wm density`）
- CPU / 内存信息（`/proc/cpuinfo`、`/proc/meminfo`）
- 截图（`screencap -p`）
- 重启（普通 / Bootloader / Recovery / Fastboot）

### 设置与主题
- **深色模式** — Material 3 动态主题
- **屏幕常亮** — 防止长时间操作时设备休眠
- **多语言** — 中/英文切换
- **本地保存路径** — 自定义文件下载目录
- **连接历史** — 记录最近 10 条连接地址
- **防抖导航** — 页面切换 300ms 导航锁，防止快速操作导致白屏

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.1.0 |
| UI 框架 | Jetpack Compose（BOM 2025.01.01） + Material Design 3 |
| 架构 | MVVM + Clean Architecture（UI / ViewModel / Core / Data） |
| 依赖注入 | Hilt 2.53.1 |
| 数据库 | Room 2.7.1（KSP 注解处理） |
| 偏好存储 | DataStore Preferences 1.1.1 |
| 异步 | Kotlin Coroutines 1.9.0 + Flow / SharedFlow / StateFlow |
| 序列化 | Kotlinx Serialization 1.7.3 |
| 导航 | Navigation Compose 2.8.5 |
| 构建工具 | Android Gradle Plugin 8.13.2 |
| 最低 SDK | API 24（Android 7.0） |
| 目标 SDK | API 35（Android 15） |
| JDK | Java 17 |

## 项目结构

```
app/src/main/java/com/adbhelper/app/
├── AdbHelperApplication.kt         # Hilt 入口 Application
├── MainActivity.kt                 # 主 Activity（主题/语言/屏幕常亮管理）
├── core/
│   ├── adb/
│   │   ├── AdbManager.kt           # ADB 服务器管理 + 设备连接 + LAN 扫描
│   │   ├── DeviceSession.kt        # 当前选中设备会话状态
│   │   └── ProcessPromptHelper.kt  # 交互式进程输出处理（无线配对）
│   ├── shell/
│   │   ├── ShellExecutor.kt        # Shell 命令执行器与常用命令封装
│   │   └── TransferHelper.kt       # 文件 push/pull + 流式进度回调
│   ├── script/
│   │   ├── ScriptEngine.kt         # 脚本执行引擎（变量/条件/交互/流式输出）
│   │   └── SpecialCommandHandler.kt # 特殊命令处理器（delay/input/confirm/set/echo）
│   └── terminal/
│       └── AnsiParser.kt           # ANSI 转义序列解析（彩色终端）
├── data/
│   ├── models/
│   │   └── ScriptEntity.kt         # Room 实体（脚本 + 执行历史）
│   ├── repositories/
│   │   ├── ScriptRepository.kt     # 脚本数据仓库
│   │   └── SettingsRepository.kt   # 设置数据仓库（DataStore）
│   ├── ScriptDao.kt                # Script DAO
│   └── AppDatabase.kt              # Room 数据库配置
├── di/
│   └── AppModule.kt                # Hilt 依赖注入模块（Json / DB / DAO）
├── ui/
│   ├── navigation/
│   │   └── AdbHelperNavHost.kt     # 导航图（含防抖导航机制）
│   ├── screens/
│   │   ├── HomeScreen.kt           # 主屏幕（功能卡片入口）
│   │   ├── DeviceScreen.kt         # 设备管理（连接/断开/扫描）
│   │   ├── DeviceCard.kt           # 设备信息卡片
│   │   ├── ConnectDeviceDialog.kt  # 无线连接对话框
│   │   ├── ShellScreen.kt          # Shell 终端
│   │   ├── ScriptsScreen.kt        # 脚本列表
│   │   ├── ScriptEditorScreen.kt   # 脚本编辑器
│   │   ├── ScriptInteractionBar.kt # 脚本交互（输入/确认）栏
│   │   ├── ScriptHelpDialog.kt     # 脚本帮助对话框
│   │   ├── NewScriptDialog.kt      # 新建脚本对话框
│   │   ├── CommandCard.kt          # 命令卡片组件
│   │   ├── CategoryDropdown.kt     # 分类下拉组件
│   │   ├── AppManagerPanel.kt      # 应用管理面板
│   │   ├── AppManagerDialogs.kt    # 应用管理对话框
│   │   ├── AppListItem.kt          # 应用列表项
│   │   ├── FileManagerPanel.kt     # 文件管理面板
│   │   ├── FileManagerDialogs.kt   # 文件管理对话框
│   │   ├── FileManagerUtils.kt     # 文件管理工具
│   │   ├── SettingsScreen.kt       # 设置屏幕
│   │   └── AboutScreen.kt          # 关于屏幕
│   ├── theme/
│   │   └── Theme.kt                # Material 3 主题定义
│   └── viewmodels/
│       ├── HomeViewModel.kt
│       ├── DeviceViewModel.kt
│       ├── ShellViewModel.kt
│       ├── ScriptsViewModel.kt
│       ├── ScriptEditorViewModel.kt
│       ├── AppManagerViewModel.kt
│       ├── AppManagerModels.kt
│       ├── AppTransferHandler.kt
│       ├── FileManagerViewModel.kt
│       ├── FileManagerModels.kt
│       └── SettingsViewModel.kt
```

## 路由结构

| 路由 | 页面 |
|------|------|
| `home` | 主屏幕 |
| `device` | 设备管理 |
| `shell` | Shell 终端 |
| `scripts` | 脚本列表 |
| `script_editor/{scriptId}` | 脚本编辑器 |
| `settings` | 设置 |
| `about` | 关于 |

## ADB 二进制集成

应用将 `adb` 二进制文件作为 `libadb.so` 打包进 APK 的 `jniLibs` 目录，利用 Android 的原生库提取机制：

- **架构支持** — `arm64-v8a` / `armeabi-v7a`
- **运行时提取** — 通过 `context.applicationInfo.nativeLibraryDir` 获取解压路径
- **环境配置** — HOME、`ANDROID_ADB_KEYS_PATH`、临时目录均指向应用私有目录

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 无线 ADB 连接 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `ACCESS_WIFI_STATE` | WiFi 状态与本机 IP 获取 |
| `READ_EXTERNAL_STORAGE` | 文件操作 |
| `WRITE_EXTERNAL_STORAGE` | 文件操作 |
| `MANAGE_EXTERNAL_STORAGE` | Android 11+ 完整文件访问 |
| `QUERY_ALL_PACKAGES` | 获取已安装应用列表 |
| `FOREGROUND_SERVICE` | 长时间运行任务 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 特殊用途前台服务 |
| `android.hardware.usb.host` | USB Host 模式（USB 设备连接） |

## 脚本命令参考

### 基础命令
```bash
# 普通 ADB 命令
shell getprop ro.product.model    # shell 前缀 → 在设备 shell 中执行
shell pm list packages            # 列出应用
shell wm size                     # 获取屏幕尺寸

# 直接使用 ADB 子命令
install /path/to/app.apk
pull /sdcard/file.txt ./local.txt
push ./local.txt /sdcard/
```

### 特殊命令
| 命令 | 说明 |
|------|------|
| `# 注释内容` | 注释行，跳过执行 |
| `delay N` | 延时 N 秒 |
| `input 提示文本` | 暂停脚本，等待用户输入，值存入 `$INPUT` |
| `confirm 提示文本` | 暂停脚本，等待用户确认 |
| `set VAR=value` | 赋值变量 |
| `set /p VAR=提示` | 交互输入到变量 |
| `set /a VAR=表达式` | 算术运算（支持 `+ - * /`） |
| `echo 文本` | 输出文本（支持变量展开） |
| `! 命令` | 前缀 `!` → 该命令失败时终止脚本 |

### 变量与条件
```bash
# 使用变量
set PKG=com.example.app
echo 目标包: $PKG
shell pm uninstall ${PKG}

# 条件执行（在 ScriptCommand.condition 中设置）
exists:/sdcard/Download         # 文件存在才执行
not_exists:/data/local/tmp     # 文件不存在才执行
package:com.android.chrome     # 应用存在才执行
```

## 构建与运行

### 环境要求
- Android Studio Iguana+（推荐最新稳定版）
- JDK 17
- Android SDK 35

### 构建步骤
```bash
# 调试构建
./gradlew assembleDebug

# 发布构建
./gradlew assembleRelease

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 使用流程
1. 启动应用 → 点击「启动 ADB 服务器」
2. 连接方式：
   - **USB 连接**：连接另一台 Android 设备（需 OTG），自动识别
   - **无线连接**：目标设备开启「无线调试」，输入 IP:端口，或点击「扫描局域网」
3. 配对（Android 11+ 首次连接需要）：输入配对码完成配对
4. 连接成功后即可使用 Shell、应用管理、文件管理、脚本等功能

## 许可证

本项目仅供学习和研究用途。

## 致谢

- 参考设计：甲壳虫 ADB 助手的交互模式
- UI 框架：Jetpack Compose + Material Design 3
- 依赖注入：Dagger Hilt
- 数据持久化：Room + DataStore

## Star History

<a href="https://www.star-history.com/?repos=wao-cy%2FAdb_Helper&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=wao-cy/Adb_Helper&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=wao-cy/Adb_Helper&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=wao-cy/Adb_Helper&type=date&legend=top-left" />
 </picture>
</a>
