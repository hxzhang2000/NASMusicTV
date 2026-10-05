package com.nasmusic.tv.ui.theme

import androidx.compose.ui.graphics.Color

// Design System Colors
// L12 修复（2026-10-06，代码审查报告 §5）：本文件原有 13 项顶层色值，其中 12 项
// 全仓零引用（与 NasMusicColors 的同名成员重复，易误引用）已删除。
// ⚠️ 仅保留 Accent —— 仍被 EqualizerScreen（import + 2 处使用）引用，
//    其余一律使用 NasMusicColors.*。
val Accent = Color(0xFF2dd4bf)
