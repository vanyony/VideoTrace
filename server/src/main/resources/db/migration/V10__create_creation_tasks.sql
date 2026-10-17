CREATE TABLE creation_tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    snapshot LONGTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_creation_owner_media(owner_id, media_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE creation_operations (
    operation_id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_payload LONGTEXT NOT NULL,
    snapshot LONGTEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    dispatched BOOLEAN NOT NULL DEFAULT FALSE,
    dispatch_failures INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_creation_request(owner_id, request_id),
    INDEX idx_creation_dispatch(active, updated_at),
    INDEX idx_creation_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Keep completed revisions accessible even after their source media is deleted.
CREATE TABLE creation_artifacts (
    task_id VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    snapshot LONGTEXT NOT NULL,
    PRIMARY KEY(task_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
