CREATE TABLE analysis_deliveries (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NOT NULL, media_id BIGINT NOT NULL,
 goal_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 active_scope VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
 payload LONGTEXT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 next_attempt_at TIMESTAMP(3) NULL DEFAULT CURRENT_TIMESTAMP(3),
 last_error VARCHAR(128) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(task_id), UNIQUE KEY uk_analysis_delivery_active(active_scope),
 KEY idx_analysis_delivery_due(status,next_attempt_at),
 KEY idx_analysis_delivery_media(media_id,goal_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
