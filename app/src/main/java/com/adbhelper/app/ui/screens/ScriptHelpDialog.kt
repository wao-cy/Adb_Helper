package com.adbhelper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adbhelper.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("SpellCheckingInspection")
@Composable
fun ScriptHelpDialog(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                text = "脚本使用指南",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 基本语法
                HelpSection("基本语法")
                HelpText("adb基础命令 或者 shell 命令都可执行。")
                HelpCode(
                    "devices -l\n"+
                    "shell getprop ro.product.model\n" +
                    "shell pm list packages\n" +
                    "shell dumpsys battery"
                )

                // 注释
                HelpSection("注释")
                HelpText("# 开头的行视为注释，执行时自动跳过。注释行可作为上方命令的描述说明。")
                HelpCode(
                    "# 获取设备型号\n" +
                    "shell getprop ro.product.model\n" +
                    "# 获取电池信息\n" +
                    "shell dumpsys battery"
                )

                // 遇错退出
                HelpSection("遇错退出 (!)")
                HelpText("! 前缀表示该命令执行失败时立即退出脚本。默认情况下命令失败会继续执行后续命令。")
                HelpCode(
                    "# 删除失败则退出脚本\n" +
                    "! shell rm /sdcard/temp.txt\n" +
                    "shell echo done"
                )

                // 延时
                HelpSection("延时等待")
                HelpText("delay <秒数> 让脚本暂停指定秒数后再继续。")
                HelpCode(
                    "shell input tap 500 500\n" +
                    "# 等待 3 秒\n" +
                    "delay 3\n" +
                    "shell input tap 500 800"
                )

                // 用户输入
                HelpSection("等待用户输入")
                HelpText("input <提示语> 暂停脚本并弹出输入框，用户输入的内容存入 \$INPUT 变量，后续命令中可引用。")
                HelpCode(
                    "input 请输入设备IP地址:\n" +
                    "shell ping -c 1 \$INPUT"
                )

                // 用户确认
                HelpSection("等待用户确认")
                HelpText("confirm <提示语> 暂停脚本并弹出确认框，用户点击确认后继续执行。")
                HelpCode(
                    "confirm 即将清除应用数据，是否继续？\n" +
                    "shell pm clear com.example.app"
                )

                // 变量赋值
                HelpSection("变量赋值 (set)")
                HelpText("set VAR=value 将值写入变量，支持引用其他变量。")
                HelpCode(
                    "set PKG=com.example.app\n" +
                    "set NAME=test\n" +
                    "set FULL=com.\$NAME.app\n" +
                    "set PKG=          # 清空变量"
                )

                // 交互输入到变量
                HelpSection("交互输入 (set /p)")
                HelpText("set /p VAR=提示文本 暂停脚本，用户输入的内容存入指定变量。")
                HelpCode(
                    "set /p PKG=请输入包名:\n" +
                    "set /p DIR=请输入目录路径:\n" +
                    "echo 卸载 \$PKG\n" +
                    "shell pm uninstall \$PKG"
                )

                // 算术运算
                HelpSection("算术运算 (set /a)")
                HelpText("set /a VAR=表达式 对整数做四则运算，支持 + - * / % 和括号。")
                HelpCode(
                    "set /a TOTAL=10+5\n" +
                    "set COUNT=3\n" +
                    "set /a REMAIN=\$COUNT-1\n" +
                    "set /a DOUBLE=\$COUNT*2\n" +
                    "set /a MOD=\$COUNT%2"
                )

                // 文本输出
                HelpSection("输出文本 (echo)")
                HelpText("echo 文本 输出一行文本，支持变量替换。用于调试和信息展示。")
                HelpCode(
                    "echo 开始处理...\n" +
                    "echo 目标包名: \$PKG\n" +
                    "echo 操作完成"
                )

                // 变量
                HelpSection("变量替换")
                HelpText("使用 \$变量名 或 \${变量名} 引用变量。变量来源：")
                HelpText("  1. 脚本编辑页的「常量」卡片中定义")
                HelpText("  2. set 命令赋值")
                HelpText("  3. set /p 交互输入")
                HelpText("  4. input 命令自动生成的 \$INPUT")
                HelpText("  5. capture 命令捕获的输出")
                HelpCode(
                    "# 常量卡片定义: target=com.example.app\n" +
                    "shell am force-stop \$target\n" +
                    "shell pm clear \$target"
                )

                // Shell 块执行
                HelpSection("Shell 块执行")
                HelpText("shell { ... } 将多行 shell 命令合并为一次 adb shell 调用，" +
                    "按行连接为一段多行脚本提交给 adb shell，共享上下文并支持 if/for 等完整 shell 语法。")
                HelpCode(
                    "# 多行脚本一次性发给 adb shell，支持 if/for\n" +
                    "shell {\n" +
                    "  cd /sdcard\n" +
                    "  ls\n" +
                    "}\n" +
                    "# 可与 capture 配合\n" +
                    "capture PATHS=shell {\n" +
                    "  pm path com.android.chrome\n" +
                    "}\n" +
                    "# 使用 \\$ 引用 shell 变量（避免被引擎替换）\n" +
                    "shell {\n" +
                    "  for file in /sdcard/*; do\n" +
                    "    echo \\\$file\n" +
                    "  done\n" +
                    "}\n" +
                    "# if/else 也完全支持\n" +
                    "shell {\n" +
                    "  if pm list packages | grep com.android.chrome; then\n" +
                    "    echo 已安装\n" +
                    "    pm path com.android.chrome\n" +
                    "  else\n" +
                    "    echo 未安装\n" +
                    "  fi\n" +
                    "}"
                )

                // 输出捕获
                HelpSection("输出捕获 (capture)")
                HelpText("capture VAR=<命令> 执行命令并捕获输出到变量，后续可用 if/goto/echo 引用。")
                HelpCode(
                    "capture BRAND=shell getprop ro.product.brand\n" +
                    "echo 设备品牌: \$BRAND\n" +
                    "if \$BRAND==\"google\" goto pixel_device"
                )

                // 控制流
                HelpSection("条件判断 (if)")
                HelpText("if <条件> <动作> 条件满足时执行动作，否则跳过。支持比较、定义检查、变量内容匹配。")
                HelpText("条件格式：")
                HelpText("  \$VAR==\"值\"        变量等于指定值")
                HelpText("  \$VAR!=\"值\"        变量不等于指定值")
                HelpText("  defined \$VAR       变量已定义且非空")
                HelpText("  not defined \$VAR   变量未定义或为空")
                HelpText("  exists:\$VAR 关键词    变量值包含指定关键词")
                HelpText("  not_exists:\$VAR 关键词  变量值不包含指定关键词")
                HelpText("支持的动作：goto、echo、set、capture")
                HelpCode(
                    "if \$BRAND==\"samsung\" goto samsung\n" +
                    "if defined \$INPUT echo 已输入: \$INPUT\n" +
                    "capture LIST=shell pm list packages\n" +
                    "if exists:\$LIST com.android.chrome echo 已安装Chrome\n" +
                    "echo 非三星设备\n" +
                    "shell settings put global multi_window_enabled 1\n" +
                    ":samsung"
                )

                // 标签与跳转
                HelpSection("标签与跳转 (goto / :label)")
                HelpText(":label_name 定义一个标签，goto label_name 跳转到该标签位置继续执行。标签名不能含空格。")
                HelpCode(
                    "captURE BRAND=shell getprop ro.product.brand\n" +
                    "if \$BRAND==\"samsung\" goto samsung_setup\n" +
                    "echo 非三星设备\n" +
                    "goto end\n" +
                    ":samsung_setup\n" +
                    "echo 三星设备专用配置\n" +
                    ":end\n" +
                    "echo 完成"
                )

                // 完整示例
                HelpSection("完整示例")
                HelpText("综合演示脚本（预置在「自定义」分类）：")
                HelpCode(
                    "# 1. 基本命令与输出捕获\n" +
                    "shell getprop ro.product.model\n" +
                    "capture BRAND=shell getprop ro.product.brand\n" +
                    "echo 品牌: \$BRAND\n" +
                    "# 2. if + goto 控制流\n" +
                    "if \$BRAND==\"samsung\" goto samsung\n" +
                    "goto ask\n" +
                    ":samsung\n" +
                    "shell settings put global multi_window_enabled 1\n" +
                    "# 3. 变量赋值与交互\n" +
                    ":ask\n" +
                    "set DEF_PKG=com.android.chrome\n" +
                    "set /p PKG=请输入包名:\n" +
                    "if \$PKG!=\"\" goto ok\n" +
                    "set PKG=\$DEF_PKG\n" +
                    ":ok\n" +
                    "# 4. input + confirm\n" +
                    "input 请输入操作:\n" +
                    "if \$INPUT==\"uninstall\" goto uninstall\n" +
                    "goto end\n" +
                    ":uninstall\n" +
                    "confirm 确认卸载 \$PKG？\n" +
                    "! shell pm uninstall \$PKG\n" +
                    "delay 2\n" +
                    "# 5. 算术运算\n" +
                    "set /a SCORE=85+15\n" +
                    "echo 评分: \$SCORE\n" +
                    "# 6. Shell 块（共享上下文）\n" +
                    "shell {\n" +
                    "  echo 完成验证\n" +
                    "  pm path \$PKG\n" +
                    "}\n" +
                    ":end\n" +
                    "echo 完成"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 速查表
                HelpSection("命令速查表")
                HelpCode(
                    "# 注释\n" +
                    "! cmd          遇错退出\n" +
                    "delay N        延时N秒\n" +
                    "input msg      等待输入→\$INPUT\n" +
                    "confirm msg    等待确认\n" +
                    "set VAR=val    变量赋值\n" +
                    "set /p VAR=提示 交互输入→\$VAR\n" +
                    "set /a VAR=表达式 算术运算\n" +
                    "echo 文本      输出文本\n" +
                    "capture VAR=命令 输出捕获\n" +
                    "shell { … }    Shell 块（共享上下文）\n" +
                    "if 条件 动作   条件判断\n" +
                    "goto label     跳转到标签\n" +
                    ":label         标签标记\n" +
                    "\$VAR           变量替换"
                )
                }
        }
    }
}

@Composable
private fun HelpSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun HelpText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

@Composable
private fun HelpCode(code: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = code,
            modifier = Modifier.padding(8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}
