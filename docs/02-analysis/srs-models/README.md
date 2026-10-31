# KaiXu SRS 建模文件

本目录对应 `KaiXu_Software_Requirements_Specification_Submission_Final.docx` 的 Figure 1–10。原 SRS 内保留了 Word 可编辑形状；本次从这些形状提取坐标、文字、线条和箭头，恢复为独立的 draw.io 建模文件。原 SRS 未修改。

## 打开和提交

用 diagrams.net / draw.io 打开 **`KaiXu_SRS_Models.drawio`**，底部的 10 个页面与 SRS 图号一一对应。也可从 `individual/` 打开某一张图。文字、矩形、椭圆、线段和箭头均为可编辑对象，文件不依赖截图或外部图片。

网页编辑器可以选择“设备”存储，然后通过“文件 → 从…打开 → 设备”打开文件；也可以使用 draw.io 桌面版。格式说明：[draw.io 官方原生文件格式说明](https://www.drawio.com/docs/manual/editor/save-file-formats/)。

`System Analysis & Design Assignment_1.pdf` 第 3 页要求提交 “specification document + modelling files”，并要求主要交互用例图、主要业务流程图，以及 AI 辅助组件的输入、工具和人工交接点；未限定建模工具或文件扩展名。提交时可将本目录的建模文件和 SRS 文档一起提交。`exports/` 中的 SVG 供预览和插入文档使用，后续修改以 `.drawio` 为准。

## 与 SRS 的对应关系

| SRS 图号 | 内容 | SRS 章节 | 单图文件 |
| --- | --- | --- | --- |
| Figure 1 | 系统上下文 | 2.5 | [01-system-context.drawio](individual/01-system-context.drawio) |
| Figure 2 | 课程、项目和核心对象关系 | 5.1 | [02-core-object-relationships.drawio](individual/02-core-object-relationships.drawio) |
| Figure 3 | 工作空间与资源管理用例 UC01–UC05 | 5.8 | [03-workspace-resource-use-cases.drawio](individual/03-workspace-resource-use-cases.drawio) |
| Figure 4 | 信息收集与个人规划用例 UC06–UC13 | 5.8 | [04-intake-personal-planning-use-cases.drawio](individual/04-intake-personal-planning-use-cases.drawio) |
| Figure 5 | 项目协作用例 UC14–UC20 | 5.8 | [05-project-collaboration-use-cases.drawio](individual/05-project-collaboration-use-cases.drawio) |
| Figure 6 | 外部信息获取与确认 | 5.11.1 | [06-information-retrieval-confirmation.drawio](individual/06-information-retrieval-confirmation.drawio) |
| Figure 7 | 任务排程与执行反馈 | 5.11.2 | [07-scheduling-execution-feedback.drawio](individual/07-scheduling-execution-feedback.drawio) |
| Figure 8 | 资源添加、关联和使用 | 5.11.3 | [08-resource-management-use.drawio](individual/08-resource-management-use.drawio) |
| Figure 9 | 共享项目任务与个人执行 | 5.11.4 | [09-shared-project-personal-execution.drawio](individual/09-shared-project-personal-execution.drawio) |
| Figure 10 | 辅助提取与人工确认边界 | 5.11.5 | [10-assisted-extraction-human-confirmation.drawio](individual/10-assisted-extraction-human-confirmation.drawio) |

## 还原范围

保留原图英文文字、手动换行、角色、用例编号、对象位置、泳道和连接方向。为便于不同电脑显示，英文字体统一使用 Arial，字重和字号从原文提取。原图中由多个线段组成的连线及单独的箭头保留为独立对象；修改节点位置时，应同时选择相关标签与线段，或先将它们组合。这里的 `.drawio` 是图形建模源文件，未另造 UML/XMI 语义仓库。

Figure 6–9 沿用 SRS 的正常流程。异常、失败、取消、权限及版本冲突处理按原文说明保留于 [MODEL_NOTES.md](MODEL_NOTES.md)，没有擅自增加原图未画出的判断节点或流程。Figure 10 保留辅助提取仅提供候选字段、人工确认后写入的边界。

Figure 11–34 是 UI 原型截图，不属于本次建模图还原范围。项目内未找到 SRS 第 11 章所述的 `_work/word_diagrams.py` 等历史生成脚本，也没有找到原始原型工程，因此本目录不声称恢复了这些历史文件。

## 校验和重建

已检查 10 张图、413 个源图形元素和 UC01–UC20；使用 mxGraph 4.2.2 实际解码并渲染各单图，执行文字修改后恢复，并逐图检查了输出。合并文件的各页与单图文件一致。具体证据见 [verification.json](verification.json)。

[recovery-manifest.json](recovery-manifest.json) 保留 SRS 文件 SHA-256、逐图原始形状 ID、文字和转换后坐标，可追溯到原 Word 图形。恢复脚本仅使用 Python 标准库：

```bash
python3 scripts/recover_srs_models.py /path/to/KaiXu_Software_Requirements_Specification_Submission_Final.docx --out /path/to/recovered-models
```

该命令重建合并文件、逐图文件、清单和原文说明；`exports/` 与 `verification.json` 是本次独立渲染与核验结果，命令不会重新生成它们。修改模型后，不要将旧预览和旧核验记录视作新版本的验证。
