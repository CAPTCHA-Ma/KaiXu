# SRS 图注与流程说明

以下英文图名与图后说明直接提取自 SRS。流程图沿用原图的正常流程；异常与取消分支仍以原文说明为准。

## Figure 1 System Context

SRS section 2.5; file `individual/01-system-context.drawio`.

## Figure 2 Courses, Projects and Core Object Relationships

SRS section 5.1; file `individual/02-core-object-relationships.drawio`.

A task may have no work sessions or may be divided across several sessions. Multiple tasks within a workspace can reference the same resource. Project tasks and common events are shared with members, while each user's specific work sessions remain private. Independent tasks and events do not require a course or project to be created first.

## Figure 3 Workspace and Resource Management Use Cases

SRS section 5.8; file `individual/03-workspace-resource-use-cases.drawio`.

## Figure 4 Information Intake and Personal Planning Use Cases

SRS section 5.8; file `individual/04-intake-personal-planning-use-cases.drawio`.

## Figure 5 Project Collaboration Use Cases

SRS section 5.8; file `individual/05-project-collaboration-use-cases.drawio`.

## Figure 6 External Information Retrieval to Task Confirmation

SRS section 5.11.1; file `individual/06-information-retrieval-confirmation.drawio`.

Users may also reach field confirmation through manual entry. Authorization failures stop the current retrieval and offer retrying. Ignored or cancelled candidates create no task. Incorrect dates or workspace ownership return to field editing. Duplicate records enter update checks; only field changes accepted by the user are written to local records.

## Figure 7 Task Scheduling and Execution Feedback

SRS section 5.11.2; file `individual/07-scheduling-execution-feedback.drawio`.

Invalid start and end times return to entry. For overlaps or deadline overruns, users choose to adjust or explicitly retain the arrangement. Only successfully saved items appear as formal schedules. Incomplete tasks return to scheduling. For completed tasks, users decide how to handle their own future sessions; other members handle their arrangements separately.

## Figure 8 Adding, Linking and Using Resources

SRS section 5.11.3; file `individual/08-resource-management-use.drawio`.

Failed validation returns to input without creating a resource record. Failed uploads retain the current valid version. Task links and resource records are saved separately; unlinking does not delete resources. Deletion requires confirmation of its impact on linked tasks. Attachment history steps apply only to releases that deliver attachment versioning.

## Figure 9 Shared Project Tasks to Personal Execution

SRS section 5.11.4; file `individual/09-shared-project-personal-execution.drawio`.

Members cannot access a project before confirming an invitation. After tasks are published, members schedule private sessions and update shared status individually; project views show only shared progress. Invalid permissions cause writes to be rejected. Version conflicts return the latest record and current input for the user to edit again.

## Figure 10 Assisted Extraction and Human Confirmation Boundaries

SRS section 5.11.5; file `individual/10-assisted-extraction-human-confirmation.drawio`.

Assisted field extraction is an extended capability. After the user actively selects message text, the system may suggest a title, date and requirements; this step produces only candidate fields. The system does not send messages to external platforms. Task creation, sharing scope and schedule changes all require user confirmation.

