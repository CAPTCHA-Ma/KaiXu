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
import com.kaixu.data.datasource.local.entity.ResourceEntity
import com.kaixu.data.datasource.local.dao.ResourceDao
import com.kaixu.data.datasource.local.dao.FocusSessionDao
import com.kaixu.data.datasource.local.dao.FocusSegmentDao
import com.kaixu.data.datasource.local.entity.FocusSessionEntity
import com.kaixu.data.datasource.local.entity.FocusSegmentEntity
import com.kaixu.data.datasource.local.database.migrations.Migration4To5Spec

@Database(
    entities = [TaskEntity::class, FixedEventEntity::class, WorkSessionEntity::class, WorkspaceEntity::class,
        ResourceEntity::class, FocusSessionEntity::class, FocusSegmentEntity::class],
    version = 5,
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = Migration4To5Spec::class)],
    exportSchema = true,
)
@ConstructedBy(KaiXuDatabaseConstructor::class)
abstract class KaiXuDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun fixedEventDao(): FixedEventDao
    abstract fun workSessionDao(): WorkSessionDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun resourceDao(): ResourceDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun focusSegmentDao(): FocusSegmentDao
}

// actual 由 Room 的 KSP 编译器为各目标生成，无需手写。
@Suppress("KotlinNoActualForExpect")
expect object KaiXuDatabaseConstructor : RoomDatabaseConstructor<KaiXuDatabase> {
    override fun initialize(): KaiXuDatabase
}
