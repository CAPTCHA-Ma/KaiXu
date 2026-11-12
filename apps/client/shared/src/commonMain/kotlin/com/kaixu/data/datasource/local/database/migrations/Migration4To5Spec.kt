package com.kaixu.data.datasource.local.database.migrations

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

/** 取消计划窗口裁剪。保留计划时段、计时目标及元数据，实际计时独立建表。 */
@DeleteColumn(tableName = "work_sessions", columnName = "focus_window_start")
@DeleteColumn(tableName = "work_sessions", columnName = "focus_window_end")
class Migration4To5Spec : AutoMigrationSpec
