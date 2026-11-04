package com.kaixu.data.datasource.local.database

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

@Database(entities = [TaskEntity::class, FixedEventEntity::class, WorkSessionEntity::class], version = 2, exportSchema = true)
@ConstructedBy(KaiXuDatabaseConstructor::class)
abstract class KaiXuDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun fixedEventDao(): FixedEventDao
    abstract fun workSessionDao(): WorkSessionDao
}

// actual 由 Room 的 KSP 编译器为各目标生成，无需手写。
@Suppress("KotlinNoActualForExpect")
expect object KaiXuDatabaseConstructor : RoomDatabaseConstructor<KaiXuDatabase> {
    override fun initialize(): KaiXuDatabase
}
