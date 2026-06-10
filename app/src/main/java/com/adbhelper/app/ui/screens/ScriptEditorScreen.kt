package com.adbhelper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.R
import com.adbhelper.app.ui.viewmodels.ScriptEditorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    scriptId: String,
    onNavigateBack: () -> Unit,
    onNavigateToShell: () -> Unit = {},
    viewModel: ScriptEditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddCommandDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    LaunchedEffect(scriptId) {
        viewModel.loadScript(scriptId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (scriptId == "new") stringResource(R.string.new_script) else stringResource(R.string.edit_script)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, "使用帮助")
                    }
                    IconButton(
                        onClick = {
                            viewModel.saveScript()
                            onNavigateBack()
                        }
                    ) {
                        Icon(Icons.Default.Save, stringResource(R.string.save))
                    }
                    IconButton(
                        onClick = {
                            viewModel.prepareExecuteScript()
                            onNavigateToShell()
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.execute))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddCommandDialog = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.add_command))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Script Info
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.name,
                            onValueChange = { viewModel.updateName(it) },
                            label = { Text(stringResource(R.string.script_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = uiState.description,
                            onValueChange = { viewModel.updateDescription(it) },
                            label = { Text(stringResource(R.string.description)) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        CategoryDropdown(
                            selectedCategory = uiState.category,
                            onCategorySelected = { viewModel.updateCategory(it) }
                        )
                    }
                }
            }

            // Variables Section
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.variables),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        uiState.variables.forEach { (key, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = key,
                                    onValueChange = { viewModel.updateVariableKey(key, it) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.variables)) },
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { viewModel.updateVariableValue(key, it) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.constant_value)) },
                                    singleLine = true
                                )
                                IconButton(onClick = { viewModel.removeVariable(key) }) {
                                    Icon(Icons.Default.Remove, stringResource(R.string.remove))
                                }
                            }
                        }

                        TextButton(onClick = { viewModel.addVariable() }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.add_variable))
                        }
                    }
                }
            }

            // Commands Header with edit mode toggle
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.commands),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row {
                        if (!uiState.isTextEditMode) {
                            IconButton(onClick = { showAddCommandDialog = true }) {
                                Icon(Icons.Default.Add, stringResource(R.string.add_command))
                            }
                        }
                        IconButton(onClick = { viewModel.toggleEditMode() }) {
                            Icon(
                                if (uiState.isTextEditMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.Code,
                                contentDescription = if (uiState.isTextEditMode) "表单模式" else "文本模式"
                            )
                        }
                    }
                }
            }

            if (uiState.isTextEditMode) {
                // 文本编辑模式
                item {
                    OutlinedTextField(
                        value = uiState.scriptText,
                        onValueChange = { viewModel.updateScriptText(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .defaultMinSize(minHeight = 400.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        placeholder = { Text("# 每行一条命令\n# 注释用 #\n# 忽略错误前缀 !\nshell getprop ro.product.model") }
                    )
                }
            } else {
                // 表单编辑模式
                if (uiState.commands.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.no_commands),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = uiState.commands,
                        key = { index, _ -> index }
                    ) { index, command ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            CommandCard(
                                index = index,
                                command = command,
                                onUpdate = { viewModel.updateCommand(index, it) },
                                onRemove = { viewModel.removeCommand(index) },
                                onMoveUp = { if (index > 0) viewModel.moveCommand(index, index - 1) },
                                onMoveDown = { if (index < uiState.commands.size - 1) viewModel.moveCommand(index, index + 1) }
                            )
                        }
                    }
                }
            }
        }

        // Add Command Dialog
        if (showAddCommandDialog) {
            AddCommandDialog(
                onDismiss = { showAddCommandDialog = false },
                onAdd = { command ->
                    viewModel.addCommand(command)
                    showAddCommandDialog = false
                }
            )
        }

        // Help Dialog
        if (showHelpDialog) {
            ScriptHelpDialog(onDismiss = { showHelpDialog = false })
        }
    }
}
