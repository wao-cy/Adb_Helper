package com.adbhelper.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.ui.viewmodels.RemoteControlViewModel
import kotlinx.coroutines.delay

@Composable
fun RemoteControlPanel(
    viewModel: RemoteControlViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var x by remember { mutableStateOf("") }
    var y by remember { mutableStateOf("") }
    var x1 by remember { mutableStateOf("") }
    var y1 by remember { mutableStateOf("") }
    var x2 by remember { mutableStateOf("") }
    var y2 by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    val showSecurityDialog by viewModel.showSecurityDialog.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.feedback.collect { msg ->
            val text = if (msg == "ok") "发送成功" else msg
            val toast = Toast.makeText(context, text, Toast.LENGTH_SHORT)
            toast.show()
            delay(500)
            toast.cancel()
        }
    }

    if (showSecurityDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSecurityDialog() },
            title = { Text("模拟输入失败") },
            text = {
                Text("高版本安卓系统需要开启「USB调试（安全设置）」才能使用模拟输入功能。\n\n请前往被控设备：\n设置 → 开发者选项 → 开启「USB调试（安全设置）」")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSecurityDialog() }) {
                    Text("知道了")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 行1: Home, 任务, Menu, 返回
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.home() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) { Text("Home") }
                Button(
                    onClick = { viewModel.task() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) { Text("任务") }
                Button(
                    onClick = { viewModel.menu() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) { Text("Menu") }
                Button(
                    onClick = { viewModel.back() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) { Text("返回") }
            }

            // 行2: 亮屏, 息屏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.wakeUp() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) { Text("亮屏") }
                OutlinedButton(
                    onClick = { viewModel.sleep() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) { Text("息屏") }
            }

            // 行3: 十字键 (D-Pad)
            DPadSection(
                onUp = { viewModel.dpadUp() },
                onDown = { viewModel.dpadDown() },
                onLeft = { viewModel.dpadLeft() },
                onRight = { viewModel.dpadRight() },
                onCenter = { viewModel.dpadCenter() }
            )

            // 行4: 音量
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.volUp() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) { Text("音量+") }
                OutlinedButton(
                    onClick = { viewModel.volDown() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) { Text("音量-") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 坐标点击
            Text("点击坐标", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = x,
                    onValueChange = { x = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("X") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = y,
                    onValueChange = { y = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("Y") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val xi = x.toIntOrNull()
                        val yi = y.toIntOrNull()
                        if (xi != null && yi != null) viewModel.tap(xi, yi)
                    })
                )
                Button(
                    onClick = {
                        val xi = x.toIntOrNull()
                        val yi = y.toIntOrNull()
                        if (xi != null && yi != null) viewModel.tap(xi, yi)
                    },
                    enabled = x.toIntOrNull() != null && y.toIntOrNull() != null
                ) { Text("点击") }
            }

            // 滑动
            Text("滑动", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = x1,
                        onValueChange = { x1 = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("起点 X") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        value = y1,
                        onValueChange = { y1 = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("起点 Y") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = x2,
                        onValueChange = { x2 = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("终点 X") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        value = y2,
                        onValueChange = { y2 = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("终点 Y") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            doSwipe(viewModel, x1, y1, x2, y2)
                        })
                    )
                    Button(
                        onClick = { doSwipe(viewModel, x1, y1, x2, y2) },
                        enabled = listOf(x1, y1, x2, y2).all { it.toIntOrNull() != null }
                    ) { Text("滑动") }
                }
            }

            // 输入文本
            Text("输入文本", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("输入要发送的ASCII字符") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendText(inputText)
                            inputText = ""
                        }
                    })
                )
                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendText(inputText)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank()
                ) { Text("发送") }
                OutlinedButton(onClick = { viewModel.deleteChar() }) {
                    Text("删除")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DPadSection(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCenter: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onUp,
            modifier = Modifier.width(64.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) { Text("↑") }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onLeft,
                modifier = Modifier.width(64.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) { Text("←") }
            FilledTonalButton(
                onClick = onCenter,
                modifier = Modifier.width(64.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) { Text("OK") }
            Button(
                onClick = onRight,
                modifier = Modifier.width(64.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) { Text("→") }
        }
        Button(
            onClick = onDown,
            modifier = Modifier.width(64.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) { Text("↓") }
    }
}

private fun doSwipe(
    viewModel: RemoteControlViewModel,
    x1: String, y1: String, x2: String, y2: String
) {
    val xi1 = x1.toIntOrNull()
    val yi1 = y1.toIntOrNull()
    val xi2 = x2.toIntOrNull()
    val yi2 = y2.toIntOrNull()
    if (xi1 != null && yi1 != null && xi2 != null && yi2 != null) {
        viewModel.swipe(xi1, yi1, xi2, yi2)
    }
}
