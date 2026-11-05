package com.kaixu.data.datasource.local.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.kaixu.data.datasource.local.dao.TaskDao
import com.kaixu.data.datasource.local.entity.TaskEntity
import com.kaixu.data.datasource.local.entity.FixedEventEntity
import com.kaixu.data.datasource.local.entity.WorkSessionEntity
import com.kaixu.data.datasource.local.dao.FixedEventDao
import com.kaixu.data.datasource.local.dao.WorkSessionDao
import com.kaixu.data.datasource.local.dao.WorkspaceDao
import com.kaixu.data.datasource.local.entity.WorkspaceEntity

@Database(
    entities = [TaskEntity::class, FixedEventEntity::class, WorkSessionEntity::class, WorkspaceEntity::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
@ConstructedBy(KaiXuDatabaseConstructor::class)
abstract class KaiXuDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun fixedEventDao(): FixedEventDao
    abstract fun workSessionDao(): WorkSessionDao
    abstract fun workspaceDao(): WorkspaceDao
}

// actual 由 Room 的 KSP 编译器为各目标生成，无需手写。
@Suppress("KotlinNoActualForExpect")
expect object KaiXuDatabaseConstructor : RoomDatabaseConstructor<KaiXuDatabase> {
    override fun initialize(): KaiXuDatabase
}
