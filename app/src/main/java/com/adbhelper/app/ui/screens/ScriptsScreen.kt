package com.adbhelper.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.R
import com.adbhelper.app.core.script.AdbScript
import com.adbhelper.app.ui.viewmodels.ScriptsUiState
import com.adbhelper.app.ui.viewmodels.ScriptsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEditor: (String) -> Unit,
    onNavigateToShell: () -> Unit = {},
    viewModel: ScriptsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { uiState.categories.size })
    val coroutineScope = rememberCoroutineScope()
    var showNewScriptDialog by remember { mutableStateOf(false) }

    // 防止导航动画期间误触脚本项
    var touchEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        touchEnabled = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scripts)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadPredefinedScripts() }) {
                        Icon(Icons.AutoMirrored.Filled.LibraryBooks, stringResource(R.string.load_templates))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                @Suppress("AssignedValueIsNeverRead")
                showNewScriptDialog = true
            }) {
                Icon(Icons.Default.Add, stringResource(R.string.new_script))
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
            // Category Filter
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth()
            ) {
                uiState.categories.forEachIndexed { index, category ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(ScriptsUiState.categoryDisplayNames[category] ?: category) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                val category = uiState.categories.getOrNull(page) ?: return@HorizontalPager
                val scripts = if (category == "all") {
                    uiState.scriptsByCategory.values.flatten()
                } else {
                    uiState.scriptsByCategory[category].orEmpty()
                }

                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (scripts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Code,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.no_scripts),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.tap_to_create),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(scripts) { script ->
                            ScriptCard(
                                script = script,
                                onClick = { onNavigateToEditor(script.id) },
                                onExecute = {
                                    viewModel.prepareExecuteScript(script)
                                    onNavigateToShell()
                                },
                                onDelete = { viewModel.deleteScript(script.id) }
                            )
                        }
                    }
                }
            }
            }

            // 透明覆盖层：导航动画期间屏蔽触摸
            if (!touchEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { /* 消费触摸事件 */ }
                )
            }
        }

        // New Script Dialog
        if (showNewScriptDialog) {
            NewScriptDialog(
                onDismiss = { showNewScriptDialog = false },
                onCreate = { name, description, category ->
                    viewModel.createScript(name, description, category)
                    showNewScriptDialog = false
                }
            )
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptCard(
    script: AdbScript,
    onClick: () -> Unit,
    onExecute: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = script.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (script.description.isNotBlank()) {
                        Text(
                            text = script.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.execute)) },
                            onClick = {
                                showMenu = false
                                onExecute()
                            },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            onClick = {
                                showMenu = false
                                onClick()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.commands_count, script.commands.size)) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(ScriptsUiState.categoryDisplayNames[script.category] ?: script.category) }
                )
            }
        }
    }
}
