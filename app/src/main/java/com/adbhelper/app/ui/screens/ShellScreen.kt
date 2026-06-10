package com.adbhelper.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.R
import com.adbhelper.app.core.script.ScriptExecutionState
import com.adbhelper.app.ui.viewmodels.ShellViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(
    onNavigateBack: () -> Unit,
    viewModel: ShellViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 检查待执行的脚本
    LaunchedEffect(Unit) {
        viewModel.checkPendingScript()
    }

    LaunchedEffect(uiState.outputLines.size) {
        if (uiState.outputLines.isNotEmpty()) {
            listState.animateScrollToItem(uiState.outputLines.size - 1)
        }
    }

    // 脚本交互输入状态
    var scriptInputValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.isScriptMode) "脚本执行" else stringResource(R.string.shell)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.isScriptMode) viewModel.exitScriptMode()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    // 复制全部输出
                    IconButton(onClick = {
                        val text = viewModel.copyAllOutput()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ADB Output", text))
                    }) {
                        Icon(Icons.Default.ContentCopy, "复制输出")
                    }
                    if (!uiState.isScriptMode) {
                        IconButton(onClick = { viewModel.clearOutput() }) {
                            Icon(Icons.Default.DeleteSweep, stringResource(R.string.clear))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Output Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .padding(8.dp)
                    ) {
                        items(uiState.outputLines) { outputLine ->
                            Text(
                                text = buildAnnotatedString {
                                    outputLine.spans.forEach { span ->
                                        withStyle(SpanStyle(color = span.color)) {
                                            append(span.text)
                                        }
                                    }
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }

                        if (uiState.isExecuting) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.Green
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.executing),
                                        color = Color.Yellow,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 脚本交互区：输入 / 确认
            if (uiState.isScriptMode) {
                when (val state = uiState.scriptExecutionState) {
                    is ScriptExecutionState.NeedInput -> {
                        ScriptInputBar(
                            prompt = state.prompt,
                            value = scriptInputValue,
                            onValueChange = { scriptInputValue = it },
                            onSubmit = {
                                viewModel.submitScriptInput(scriptInputValue)
                                scriptInputValue = ""
                            }
                        )
                    }
                    is ScriptExecutionState.NeedConfirm -> {
                        ScriptConfirmBar(
                            prompt = state.prompt,
                            onConfirm = { viewModel.confirmScriptContinue() },
                            onCancel = { viewModel.exitScriptMode() }
                        )
                    }
                    is ScriptExecutionState.Running -> {
                        // 显示进度
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 4.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "步骤 ${state.currentCommand}/${state.totalCommands}  ${state.commandDescription}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    else -> {}
                }
            }

            // 普通模式：命令输入 + 快捷命令
            if (!uiState.isScriptMode) {
                // Command Input
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (uiState.isInteractiveMode) "shell$" else "$",
                            color = MaterialTheme.colorScheme.outline,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )

                        OutlinedTextField(
                            value = uiState.currentCommand,
                            onValueChange = { viewModel.updateCommand(it) },
                            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionUp -> { viewModel.previousCommand(); true }
                                        Key.DirectionDown -> { viewModel.nextCommand(); true }
                                        else -> false
                                    }
                                } else false
                            },
                            placeholder = {
                                Text(
                                    if (uiState.isInteractiveMode) "输入 'exit' 退出终端" else stringResource(R.string.enter_command),
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { viewModel.executeCommand() }
                            ),
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { viewModel.executeCommand() },
                            enabled = uiState.currentCommand.isNotBlank() && (!uiState.isExecuting || uiState.isInteractiveMode)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.execute),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Quick Commands
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { QuickCommandChip("devices") { viewModel.executeQuickCommand("devices") } }
                        item { QuickCommandChip("shell") { viewModel.executeQuickCommand("shell") } }
                        item { QuickCommandChip("exit") { viewModel.executeQuickCommand("exit") } }
                        item { QuickCommandChip("getprop") { viewModel.executeQuickCommand("getprop") } }
                        item { QuickCommandChip("packages") { viewModel.executeQuickCommand("packages") } }
                        item { QuickCommandChip("battery") { viewModel.executeQuickCommand("battery") } }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickCommandChip(
    label: String,
    onClick: () -> Unit
) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(label) }
    )
}
