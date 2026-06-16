# 汪汪ADB助手

基于 Android 设备直接运行 ADB 命令的调试工具，支持脚本管理、应用管理、文件传输。内置 ADB 二进制，无需 Root，USB 或无线连接管理设备。

## 功能特性

- **ADB 管理** — 启动/停止 ADB server，USB 和无线连接，局域网并发扫描，Android 11+ 配对
- **交互式 Shell** — 流式终端，ANSI 彩色输出
- **脚本引擎** — 顺序执行命令，支持超时、错误处理、条件执行、变量替换
- **自定义命令** — `set` / `echo` / `delay` / `input` / `confirm` / `capture` / `if` / `goto` / `:label`
- **应用管理** — 启动/停止/清除数据/卸载/禁用/启用，APK 上传安装，图标解析，应用名缓存
- **进程管理** — 查看进程列表（PID/CPU/内存），终止进程
- **文件管理** — 浏览/上传/下载/多选批量操作/复制粘贴/重命名/文本预览
- **设备信息** — 属性、电池、存储、屏幕、CPU/内存、截图、重启

## 技术栈

| 类别     | 技术                                                      |
| -------- | --------------------------------------------------------- |
| 语言     | Kotlin 2.1.0                                              |
| UI 框架  | Jetpack Compose（BOM 2025.01.01） + Material Design 3     |
| 架构     | MVVM + Clean Architecture（UI / ViewModel / Core / Data） |
| 依赖注入 | Hilt 2.53.1                                               |
| 数据库   | Room 2.7.1（KSP 注解处理）                                |
| 偏好存储 | DataStore Preferences 1.1.1                               |
| 异步     | Kotlin Coroutines 1.9.0 + Flow / SharedFlow / StateFlow   |
| 序列化   | Kotlinx Serialization 1.7.3                               |
| 导航     | Navigation Compose 2.8.5                                  |
| 构建工具 | Android Gradle Plugin 8.13.2                              |
| 最低 SDK | API 24（Android 7.0）                                     |
| 目标 SDK | API 35（Android 15）                                      |
| JDK      | Java 17                                                   |

## 目录结构

```
app/src/main/java/com/adbhelper/app/
├── core/
│   ├── adb/
│   │   ├── AdbManager.kt           # 进程管理、设备列表、连接/断开/配对/扫描
│   │   ├── DeviceSession.kt        # 当前选中设备序列号
│   │   └── ProcessPromptHelper.kt  # 交互式进程工具
│   ├── script/
│   │   ├── ScriptEngine.kt         # 脚本解析与执行（while 循环支持 goto）
│   │   ├── SpecialCommandHandler.kt # 特殊命令处理器集（delay/input/confirm/set/echo/if/goto/label/capture）
│   │   └── ArithEval.kt            # 整数表达式求值（set /a 用）
│   ├── shell/
│   │   ├── ShellExecutor.kt
│   │   ├── TransferHelper.kt       # push/pull
│   │   └── LsOutputParser.kt       # ls 解析 + 符号链接推断
│   └── terminal/AnsiParser.kt
├── data/
│   ├── AppDatabase.kt / ScriptDao.kt / ScriptEntity.kt
│   └── repositories/
│       ├── ScriptRepository.kt
│       └── SettingsRepository.kt   # 设置/连接历史 (DataStore)
├── ui/
│   ├── theme/Theme.kt
│   ├── navigation/AdbHelperNavHost.kt
│   └── screens/ + viewmodels/      # 按功能模块一一对应
```

## 脚本命令参考

### 基础命令
```bash
# 普通 ADB 命令
shell getprop ro.product.model    # shell 前缀 → 在设备 shell 中执行
shell pm list packages

# 直接使用 ADB 子命令
install /path/to/app.apk
pull /sdcard/file.txt ./local.txt
push ./local.txt /sdcard/
```

### 特殊命令
| 命令 | 说明 |
|------|------|
| `# 注释` | 注释行，跳过执行 |
| `delay N` | 延时 N 秒 |
| `input 提示` | 等待用户输入，值存入 `$INPUT` |
| `confirm 提示` | 等待用户确认 |
| `set VAR=val` | 变量赋值 |
| `set /p VAR=提示` | 交互输入到变量 |
| `set /a VAR=表达式` | 算术运算（`+ - * / %` 和括号） |
| `echo 文本` | 输出文本，支持 `$VAR` 替换 |
| `capture VAR=命令` | 执行命令，输出存入变量 |
| `:label_name` | 标签标记（跳转目标） |
| `goto label` | 跳转到标签 |
| `if 条件 动作` | 条件判断（`$VAR=="值"` / `defined $VAR` / `exists:路径`） |
| `! 命令` | 命令失败时终止脚本 |
| `$VAR` / `${VAR}` | 变量引用 |

### 条件执行（命令卡片的 condition 字段）
- `exists:路径` — 文件存在才执行
- `not_exists:路径` — 文件不存在才执行
- `package:包名` — 应用存在才执行

### 控制流示例
```bash
capture BRAND=shell getprop ro.product.brand
if $BRAND=="samsung" goto samsung
echo 非三星设备
goto end

:samsung
echo 检测到三星设备!
shell settings put global multi_window_enabled 1
:end
```

## 使用流程

1. 启动应用 → 启动 ADB 服务器
2. 连接：USB（需 OTG）或无线（输入 IP:端口 / 扫描局域网）
3. Android 11+ 首次无线连接需配对
4. 使用 Shell、应用管理、文件管理、脚本等功能

## 构建

```bash
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

需要 **JDK 17** + **Android SDK 35**。

## 致谢

- 参考设计：甲壳虫 ADB 助手

## 自愿赞赏支持

❤如果项目对你有帮助，欢迎赞助Token，用于后续功能迭代与维护，Token一停，天才归零😭

 <picture>
     <img alt="微信赞赏" src="zan.jpg" width="200" />
 </picture>



## Star History

<a href="https://www.star-history.com/?repos=wao-cy%2FAdb_Helper&type=date&legend=top-left">

 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=wao-cy/Adb_Helper&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=wao-cy/Adb_Helper&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=wao-cy/Adb_Helper&type=date&legend=top-left" />
 </picture>
</a>
