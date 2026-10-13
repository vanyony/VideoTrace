CREATE TABLE upload_sessions (
    upload_id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    total_chunks INT NOT NULL,
    expected_bytes BIGINT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    media_id BIGINT NULL,
    cleanup_pending BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at DATETIME(3) NOT NULL,
    cleanup_after DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_upload_cleanup (cleanup_pending, cleanup_after)
);
CREATE TABLE upload_parts (
    upload_id CHAR(36) NOT NULL,
    part_index INT NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum CHAR(64) NOT NULL,
    PRIMARY KEY (upload_id, part_index)
);
ALTER TABLE media_files ADD COLUMN source_upload_id CHAR(36) NULL,
    ADD UNIQUE KEY uk_media_source_upload (source_upload_id);
