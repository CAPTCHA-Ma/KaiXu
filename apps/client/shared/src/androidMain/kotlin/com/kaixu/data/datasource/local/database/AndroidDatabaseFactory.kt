package com.kaixu.data.datasource.local.database

import android.content.Context
import androidx.room.Room

/** 使用应用私有数据库目录，避免持有 Activity；复用公共驱动和迁移配置。 */
fun openAndroidDatabase(context: Context): KaiXuDatabase {
    val applicationContext = context.applicationContext
    return buildDatabase(
        Room.databaseBuilder<KaiXuDatabase>(
            context = applicationContext,
            name = applicationContext.getDatabasePath("kaixu.db").absolutePath,
        )
    )
}
