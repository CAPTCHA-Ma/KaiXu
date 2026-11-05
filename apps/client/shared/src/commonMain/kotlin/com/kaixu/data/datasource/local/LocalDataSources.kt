package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.datasource.local.database.KaiXuDatabase

/** 本地数据源共享数据库；实例的打开和关闭由宿主管理。 */
class LocalDataSources(database: KaiXuDatabase) {
    val workspaces: WorkspaceDataSource = RoomWorkspaceDataSource(database.workspaceDao())
    val tasks: TaskDataSource = RoomTaskDataSource(database.taskDao())
    val fixedEvents: FixedEventDataSource = RoomFixedEventDataSource(database.fixedEventDao())
    val workSessions: WorkSessionDataSource = RoomWorkSessionDataSource(database.workSessionDao())
}
