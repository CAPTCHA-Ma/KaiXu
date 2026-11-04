package com.kaixu.data.mapper

import com.kaixu.data.datasource.local.entity.TaskEntity
import com.kaixu.domain.model.tasks.Task

internal fun TaskEntity.toDomain(): Task = Task(id = id, title = title)
