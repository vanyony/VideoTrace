CREATE TABLE analysis_executions (
 creation_order BIGINT NOT NULL AUTO_INCREMENT,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 media_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 goal_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 active_scope VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 execution_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until TIMESTAMP(3) NULL,
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(128) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(task_id), UNIQUE KEY uk_analysis_execution_order(creation_order), UNIQUE KEY uk_analysis_execution_active(active_scope),
 KEY idx_analysis_execution_expired(state,lease_until),
 KEY idx_analysis_execution_latest(media_id,goal_digest,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Existing observed messages may belong to the previous consumer; do not replay completed work.
INSERT INTO analysis_executions(task_id,user_id,media_id,goal_digest,active_scope,state)
SELECT task_id,user_id,media_id,goal_digest,
       IF(status='OBSERVED',NULL,CONCAT(media_id,':',goal_digest)),
       IF(status='OBSERVED','LEGACY','QUEUED')
FROM analysis_deliveries;
