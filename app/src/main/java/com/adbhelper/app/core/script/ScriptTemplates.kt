package com.adbhelper.app.core.script

object ScriptTemplates {

    val COMPREHENSIVE_DEMO_SCRIPT = AdbScript(
        id = "comprehensive_demo",
        name = "综合命令演示",
        description = "全面展示脚本引擎所有命令：echo / set / set /p / set /a / capture / if / goto / delay / input / confirm，以及控制流、输出捕获等高级功能",
        category = "custom",
        commands = listOf(
            ScriptCommand("# ===== 综合命令演示 — 覆盖所有自定义命令 =====\n# echo、delay、input、confirm、set、set /p、set /a\n# capture、if、goto、:label、!、\$变量引用", ""),

            ScriptCommand("echo ========== 1. 基本命令 ==========", ""),
            ScriptCommand("shell getprop ro.product.model", "设备型号"),
            ScriptCommand("shell getprop ro.build.version.release", "系统版本"),

            ScriptCommand("capture BRAND=shell getprop ro.product.brand", "捕获输出到变量"),
            ScriptCommand("echo 设备品牌: \$BRAND", "引用捕获的变量"),

            ScriptCommand("if \$BRAND==\"samsung\" goto samsung_device", "三星设备分支"),
            ScriptCommand("echo 非三星设备，跳过三星专用设置", ""),
            ScriptCommand("goto ask_pkg", "跳转到输入环节"),

            ScriptCommand(":samsung_device", "标签：三星设备"),
            ScriptCommand("echo 检测到三星设备，启用多窗口模式", ""),
            ScriptCommand("shell settings put global multi_window_enabled 1", "启用多窗口"),
            ScriptCommand("delay 1", ""),

            ScriptCommand(":ask_pkg", "标签：询问包名"),
            ScriptCommand("echo ========== 2. 变量赋值与交互 ==========", ""),
            ScriptCommand("set DEF_PKG=com.android.chrome", "设置默认包名"),
            ScriptCommand("set DEF_ACTION=install", "设置默认操作"),
            ScriptCommand("echo 默认包名: \$DEF_PKG，默认操作: \$DEF_ACTION", ""),

            ScriptCommand("set /p PKG=请输入包名(留空用默认):", "交互输入包名"),
            ScriptCommand("set /p ACTION=请输入操作(install/uninstall):", "交互输入操作"),

            ScriptCommand("if \$PKG!=\"\" goto use_custom_pkg", "自定义包名"),
            ScriptCommand("set PKG=\$DEF_PKG", "使用默认包名"),
            ScriptCommand("goto check_action", ""),

            ScriptCommand(":use_custom_pkg", "标签：自定义包名"),
            ScriptCommand("echo 使用自定义包名: \$PKG", ""),

            ScriptCommand(":check_action", "标签：判断操作类型"),
            ScriptCommand("if \$ACTION==\"uninstall\" goto do_uninstall", ""),
            ScriptCommand("if \$ACTION==\"install\" goto do_install", ""),
            ScriptCommand("echo 未知操作: \$ACTION，使用默认操作", ""),
            ScriptCommand("set ACTION=\$DEF_ACTION", ""),
            ScriptCommand("if \$ACTION==\"uninstall\" goto do_uninstall", ""),
            ScriptCommand("echo 执行默认操作: \$ACTION", ""),
            ScriptCommand("goto end", ""),

            ScriptCommand(":do_uninstall", "标签：卸载流程"),
            ScriptCommand("echo ========== 3. 交互确认 ==========", ""),
            ScriptCommand("input 请输入卸载原因(可选，直接回车跳过):", "输入反馈"),
            ScriptCommand("if \$INPUT!=\"\" echo 卸载原因已记录: \$INPUT", ""),
            ScriptCommand("confirm 确认卸载 \$PKG ？此操作不可撤销。", "确认卸载"),
            ScriptCommand("! shell pm uninstall \$PKG", "执行卸载(失败则退出)"),
            ScriptCommand("delay 2", "等待 2 秒"),
            ScriptCommand("echo 卸载完成，验证结果...", ""),
            ScriptCommand("shell pm list packages | grep \$PKG", "验证卸载(应失败)"),

            ScriptCommand("echo ========== 4. 算术运算 ==========", ""),
            ScriptCommand("set /a SCORE=85+15", "算术: 加法"),
            ScriptCommand("echo 评分: \$SCORE", ""),
            ScriptCommand("set COUNT=3", ""),
            ScriptCommand("set /a REMAIN=\$COUNT-1", "算术: 减法"),
            ScriptCommand("set /a DOUBLE=\$COUNT*2", "算术: 乘法"),
            ScriptCommand("echo 剩余: \$REMAIN，双倍: \$DOUBLE", ""),
            ScriptCommand("goto end", ""),

            ScriptCommand(":do_install", "标签：安装流程"),
            ScriptCommand("echo ========== 5. 安装流程 ==========", ""),
            ScriptCommand("input 请输入APK路径(默认 /sdcard/app.apk):", ""),
            ScriptCommand("if \$INPUT!=\"\" goto set_apk_path", ""),
            ScriptCommand("set APK_PATH=/sdcard/app.apk", ""),
            ScriptCommand("goto do_install_exec", ""),
            ScriptCommand(":set_apk_path", ""),
            ScriptCommand("set APK_PATH=\$INPUT", ""),

            ScriptCommand(":do_install_exec", ""),
            ScriptCommand("echo 安装路径: \$APK_PATH", ""),
            ScriptCommand("shell pm install \$APK_PATH", "安装APK"),
            ScriptCommand("delay 1", ""),

            ScriptCommand(":end", "标签：结束"),
            ScriptCommand("echo ========== 演示结束 ==========", ""),
            ScriptCommand("echo 本脚本演示了所有命令的用法。", ""),

            ScriptCommand("# 提示: 以下命令支持在「条件」字段设置条件\n# exists:路径 — 文件存在才执行\n# not_exists:路径 — 文件不存在才执行\n# package:包名 — 应用存在才执行", "")
        )
    )

    val PREDEFINED_SCRIPTS = listOf(COMPREHENSIVE_DEMO_SCRIPT)
}
