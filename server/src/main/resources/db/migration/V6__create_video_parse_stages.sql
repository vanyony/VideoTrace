CREATE TABLE video_parse_stages (
 media_id BIGINT NOT NULL,
 parse_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 branch VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL,
 payload LONGTEXT NULL,
 last_error VARCHAR(128) NULL,
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(media_id,parse_version,branch)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
