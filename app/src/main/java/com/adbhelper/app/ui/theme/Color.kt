package com.adbhelper.app.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════
//  ADB Helper App — Design Token System
//  ═══════════════════════════════════════════════
//
//  角色映射:
//  元素类型                          → 使用 token
//  ─────────────────────────────────────────────────
//  主按钮 / 选中态 Tab / 链接        → primary
//  强调按钮 / FAB / Tab indicator    → secondary
//  特殊标签 / 成功反馈 / 符号链接    → tertiary (青绿)
//  TopAppBar / 弹窗标题 / 分割标题   → surfaceContainerHighest
//  Card 背景 (普通)                  → surfaceContainerHigh
//  Card 背景 (次要/表单)             → surfaceContainer
//  多选 / 选中状态                   → secondaryContainer
//  危险操作                          → error

// ── Primary: 金色（主色调） ──
val primaryLight = Color(0xFF6D5E0F)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFFF8E287)
val onPrimaryContainerLight = Color(0xFF534600)
val primaryDark = Color(0xFFDBC66E)
val onPrimaryDark = Color(0xFF3A3000)
val primaryContainerDark = Color(0xFF4E472A)
val onPrimaryContainerDark = Color(0xFFF8E287)

// ── Secondary: 暖沙色（强调动作、选中态、FAB） ──
val secondaryLight = Color(0xFF7E5C3B)
val onSecondaryLight = Color(0xFFFFFFFF)
val secondaryContainerLight = Color(0xFFF8E287)
val onSecondaryContainerLight = Color(0xFF4A3726)
val secondaryDark = Color(0xFFE9BF98)
val onSecondaryDark = Color(0xFF3E210E)
val secondaryContainerDark = Color(0xFF4E472A)
val onSecondaryContainerDark = Color(0xFFF0DCC8)

// ── Tertiary: 青绿（特殊标记、成功反馈、符号链接） ──
val tertiaryLight = Color(0xFF006B5E)
val onTertiaryLight = Color(0xFFFFFFFF)
val tertiaryContainerLight = Color(0xFFC5ECCE)
val onTertiaryContainerLight = Color(0xFF003830)
val tertiaryDark = Color(0xFF00D5BE)
val onTertiaryDark = Color(0xFF003830)
val tertiaryContainerDark = Color(0xFF2C4E38)
val onTertiaryContainerDark = Color(0xFF7CF2DE)

// ── Error ──
val errorLight = Color(0xFFBA1A1A)
val onErrorLight = Color(0xFFFFFFFF)
val errorContainerLight = Color(0xFFFFDAD6)
val onErrorContainerLight = Color(0xFF93000A)
val errorDark = Color(0xFFFFB4AB)
val onErrorDark = Color(0xFF690005)
val errorContainerDark = Color(0xFF93000A)
val onErrorContainerDark = Color(0xFFFFDAD6)

// ── Surface / Background（中性灰色，无黄相 ──
//
//  层级关系 (light):
//                  明度
//  surfaceBright       #F8F8F8  ← 最亮
//  surfaceContainerLowest #FFFFFF ← 纯白(输入框背景)
//  surfaceContainerLow    #F2F2F3
//  surfaceContainer      #ECECED  ← 次要卡片(如表单区)
//  surfaceContainerHigh  #E5E5E7  ← 普通卡片背景
//  surfaceContainerHeight #DFDFE1 ← TopAppBar / 分割标题
//  surfaceDim            #D9D9DC  ← 最暗(下拉菜单)
//                  明度↓

val backgroundLight = Color(0xFFF8F8F8)
val onBackgroundLight = Color(0xFF1A1A1C)
val surfaceLight = Color(0xFFF8F8F8)
val onSurfaceLight = Color(0xFF1A1A1C)
val surfaceVariantLight = Color(0xFFE6E6E6)
val onSurfaceVariantLight = Color(0xFF46464A)
val outlineLight = Color(0xFF767680)
val outlineVariantLight = Color(0xFFC5C5C8)
val scrimLight = Color(0xFF000000)
val inverseSurfaceLight = Color(0xFF303034)
val inverseOnSurfaceLight = Color(0xFFF4F4F5)
val inversePrimaryLight = Color(0xFFDBC66E)
val surfaceDimLight = Color(0xFFD9D9DC)
val surfaceBrightLight = Color(0xFFF8F8F8)
val surfaceContainerLowestLight = Color(0xFFFFFFFF)
val surfaceContainerLowLight = Color(0xFFF2F2F3)
val surfaceContainerLight = Color(0xFFECECED)
val surfaceContainerHighLight = Color(0xFFE5E5E7)
val surfaceContainerHighestLight = Color(0xFFDFDFE1)

//  层级关系 (dark):
//                  明度
//  surfaceDim            #121214  ← 最暗(主背景)
//  surfaceContainerLowest #0A0A0D
//  surfaceContainerLow    #1A1A1C
//  surfaceContainer      #242427  ← 次要卡片
//  surfaceContainerHigh  #2E2E31  ← 普通卡片
//  surfaceContainerHeight #39393C ← TopAppBar
//  surfaceBright         #3A3A3E
//                  明度↑

val backgroundDark = Color(0xFF121214)
val onBackgroundDark = Color(0xFFE3E3E5)
val surfaceDark = Color(0xFF121214)
val onSurfaceDark = Color(0xFFE3E3E5)
val surfaceVariantDark = Color(0xFF454549)
val onSurfaceVariantDark = Color(0xFFC5C5C8)
val outlineDark = Color(0xFF8F8F95)
val outlineVariantDark = Color(0xFF454549)
val scrimDark = Color(0xFF000000)
val inverseSurfaceDark = Color(0xFFE3E3E5)
val inverseOnSurfaceDark = Color(0xFF303034)
val inversePrimaryDark = Color(0xFF6D5E0F)
val surfaceDimDark = Color(0xFF121214)
val surfaceBrightDark = Color(0xFF3A3A3E)
val surfaceContainerLowestDark = Color(0xFF0A0A0D)
val surfaceContainerLowDark = Color(0xFF1A1A1C)
val surfaceContainerDark = Color(0xFF242427)
val surfaceContainerHighDark = Color(0xFF2E2E31)
val surfaceContainerHighestDark = Color(0xFF39393C)
