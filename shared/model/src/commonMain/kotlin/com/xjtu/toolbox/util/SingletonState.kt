package com.xjtu.toolbox.util

import androidx.compose.runtime.snapshots.Snapshot

/**
 * 全局单例（`object`）里的 Compose state 一律用它建：`var x by singletonState { mutableIntStateOf(0) }`。
 *
 * 单例可能先被后台线程初始化（小组件、壁纸、Agent 工具）。在全局快照里建的 state 只有一条「此刻」的记录，
 * 已经开始的组合快照读它会抛 "Reading a state that was created after the snapshot was taken"。
 * 在临时快照里建，runtime 会额外挂一条所有快照都可见的初始记录。
 */
fun <T> singletonState(create: () -> T): T = Snapshot.global { Snapshot.withMutableSnapshot(create) }
