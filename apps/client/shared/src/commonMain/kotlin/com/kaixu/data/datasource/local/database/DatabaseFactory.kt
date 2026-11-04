package com.kaixu.data.datasource.local.database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import com.kaixu.data.datasource.local.database.migrations.Migration1To2

/** 平台提供 builder；驱动及公共配置集中在此，不启用破坏性重建。 */
fun buildDatabase(builder: RoomDatabase.Builder<KaiXuDatabase>): KaiXuDatabase =
    builder
        .addMigrations(Migration1To2)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
