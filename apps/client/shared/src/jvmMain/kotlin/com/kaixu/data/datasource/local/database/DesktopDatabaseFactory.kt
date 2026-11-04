package com.kaixu.data.datasource.local.database

import androidx.room.Room
import java.io.File

/** 默认使用持久用户目录；显式路径用于测试及未来的账号分库。 */
fun openDesktopDatabase(file: File = defaultDesktopDatabaseFile()): KaiXuDatabase {
    val databaseFile = file.absoluteFile
    val directory = requireNotNull(databaseFile.parentFile)
    check(directory.isDirectory || directory.mkdirs()) {
        "无法创建数据库目录：$directory"
    }
    return buildDatabase(Room.databaseBuilder<KaiXuDatabase>(name = databaseFile.path))
}

fun defaultDesktopDatabaseFile(): File {
    val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        ?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
    return File(base, "KaiXu/local/kaixu.db")
}
