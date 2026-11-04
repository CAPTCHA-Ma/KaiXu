package com.kaixu.data.datasource.local.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.kaixu.data.datasource.local.entity.currentTimeMillis
import com.kaixu.data.datasource.local.entity.newLocalId

/** 固定的版本 1 → 2 转换，旧整数 ID 改为 UUID；版本 1 没有关联表。 */
object Migration1To2 : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `tasks_v2` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `workspace_id` TEXT, `created_by` TEXT NOT NULL, `assignee_user_id` TEXT, `status` TEXT NOT NULL, `priority` TEXT NOT NULL, `deadline_date` TEXT, `deadline_at` INTEGER, `estimated_minutes` INTEGER, `blocked_note` TEXT, `completed_at` INTEGER, `source_excerpt` TEXT, `source_url` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `updated_by` TEXT NOT NULL, `deleted_at` INTEGER, `server_version` INTEGER, `local_revision` INTEGER NOT NULL, `sync_state` TEXT NOT NULL, PRIMARY KEY(`id`))""")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `fixed_events` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `starts_at` INTEGER NOT NULL, `ends_at` INTEGER NOT NULL, `description` TEXT, `workspace_id` TEXT, `created_by` TEXT NOT NULL, `reminder_offset_minutes` INTEGER, `series_id` TEXT, `original_date` TEXT, `is_override` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `updated_by` TEXT NOT NULL, `deleted_at` INTEGER, `server_version` INTEGER, `local_revision` INTEGER NOT NULL, `sync_state` TEXT NOT NULL, PRIMARY KEY(`id`))""")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `work_sessions` (`id` TEXT NOT NULL, `task_id` TEXT, `starts_at` INTEGER NOT NULL, `ends_at` INTEGER NOT NULL, `owner_user_id` TEXT NOT NULL, `stage_id` TEXT, `note` TEXT, `status` TEXT NOT NULL, `is_locked` INTEGER NOT NULL, `overlap_confirmed` INTEGER NOT NULL, `detached_at` INTEGER, `detach_reason` TEXT, `focus_window_start` INTEGER, `focus_window_end` INTEGER, `focus_target_seconds` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `updated_by` TEXT NOT NULL, `deleted_at` INTEGER, `server_version` INTEGER, `local_revision` INTEGER NOT NULL, `sync_state` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`task_id`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""")
        val at = currentTimeMillis()
        connection.prepare("SELECT title FROM tasks ORDER BY id").use { oldRows ->
            connection.prepare("""INSERT INTO tasks_v2
                (id, title, created_by, status, priority, created_at, updated_at, updated_by, local_revision, sync_state)
                VALUES (?, ?, '00000000-0000-0000-0000-000000000001', 'TODO', 'NORMAL', ?, ?, '00000000-0000-0000-0000-000000000001', 0, 'LOCAL_ONLY')""").use { insert ->
                var order = 0L
                while (oldRows.step()) {
                    insert.bindText(1, newLocalId())
                    insert.bindText(2, oldRows.getText(0))
                    insert.bindLong(3, at + order++)
                    insert.bindLong(4, at)
                    insert.step()
                    insert.reset()
                }
            }
        }
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_fixed_events_series_id_original_date ON fixed_events (series_id, original_date)")
        connection.execSQL("DROP TABLE tasks")
        connection.execSQL("ALTER TABLE tasks_v2 RENAME TO tasks")
        connection.execSQL("""CREATE INDEX IF NOT EXISTS `index_tasks_workspace_id_status` ON `tasks` (`workspace_id`, `status`)""")
        connection.execSQL("""CREATE INDEX IF NOT EXISTS `index_tasks_assignee_user_id_status` ON `tasks` (`assignee_user_id`, `status`)""")
        connection.execSQL("""CREATE INDEX IF NOT EXISTS `index_fixed_events_workspace_id_starts_at` ON `fixed_events` (`workspace_id`, `starts_at`)""")
        connection.execSQL("""CREATE INDEX IF NOT EXISTS `index_fixed_events_created_by_starts_at` ON `fixed_events` (`created_by`, `starts_at`)""")
        connection.execSQL("""CREATE INDEX IF NOT EXISTS `index_work_sessions_task_id` ON `work_sessions` (`task_id`)""")
        connection.execSQL("""CREATE INDEX IF NOT EXISTS `index_work_sessions_owner_user_id_starts_at` ON `work_sessions` (`owner_user_id`, `starts_at`)""")
    }
}
