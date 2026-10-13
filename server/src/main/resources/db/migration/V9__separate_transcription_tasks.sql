ALTER TABLE analysis_executions
 ADD COLUMN task_kind VARCHAR(16) NOT NULL DEFAULT 'ANALYSIS',
 ADD KEY idx_execution_kind_latest(media_id,task_kind,creation_order);
