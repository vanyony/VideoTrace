import json
from pathlib import Path
import sqlite3
import time
from contextlib import contextmanager

from .models import Operation


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS operations (
                    operation_id TEXT, attempt INTEGER, payload TEXT NOT NULL,
                    status TEXT NOT NULL, created REAL NOT NULL,
                    PRIMARY KEY(operation_id, attempt));
                CREATE TABLE IF NOT EXISTS events (
                    operation_id TEXT, attempt INTEGER, sequence INTEGER, task_id TEXT,
                    payload TEXT NOT NULL, delivered INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(operation_id, attempt, sequence));
                CREATE TABLE IF NOT EXISTS tool_traces (
                    id INTEGER PRIMARY KEY, operation_id TEXT, attempt INTEGER,
                    name TEXT, elapsed_ms INTEGER, result TEXT, created REAL);
            """)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def accept(self, operation: Operation):
        payload = encoded(operation.model_dump())
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT * FROM operations WHERE operation_id=? ORDER BY attempt DESC LIMIT 1", (operation.operationId,)).fetchone()
            if existing:
                if existing["attempt"] == operation.attempt and existing["payload"] == payload:
                    return False
                if existing["attempt"] >= operation.attempt or existing["status"] not in ("DONE", "FAILED"):
                    raise ValueError("operation identity already used with different payload or active attempt")
                original, retried = json.loads(existing["payload"]), operation.model_dump()
                original.pop("attempt")
                retried.pop("attempt")
                if original != retried:
                    raise ValueError("a new attempt cannot change the bound operation payload")
            db.execute("INSERT INTO operations VALUES(?,?,?,?,?)", (operation.operationId, operation.attempt, payload, "PENDING", time.time()))
            return True

    def recover(self):
        with self.connect() as db:
            db.execute("UPDATE operations SET status='PENDING' WHERE status='RUNNING'")

    def claim(self):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM operations WHERE status='PENDING' ORDER BY created LIMIT 1").fetchone()
            if row:
                db.execute("UPDATE operations SET status='RUNNING' WHERE operation_id=? AND attempt=?", (row["operation_id"], row["attempt"]))
                return Operation.model_validate_json(row["payload"])

    def latest(self, operation_id):
        with self.connect() as db:
            row = db.execute("SELECT * FROM operations WHERE operation_id=? ORDER BY attempt DESC LIMIT 1", (operation_id,)).fetchone()
            if not row:
                return None
            event = db.execute("SELECT payload FROM events WHERE operation_id=? AND attempt=? ORDER BY sequence DESC LIMIT 1", (operation_id, row["attempt"])).fetchone()
            return {"operationId": operation_id, "attempt": row["attempt"], "status": row["status"], "event": json.loads(event[0]) if event else None}

    def emit(self, operation: Operation, status, summary, **fields):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            seq = db.execute("SELECT COALESCE(MAX(sequence),0)+1 FROM events WHERE operation_id=? AND attempt=?", (operation.operationId, operation.attempt)).fetchone()[0]
            event = {"operationId": operation.operationId, "attempt": operation.attempt, "sequence": seq,
                     "status": status, "summary": summary, **fields}
            db.execute("INSERT INTO events(operation_id,attempt,sequence,task_id,payload) VALUES(?,?,?,?,?)",
                       (operation.operationId, operation.attempt, seq, operation.taskId, encoded(event)))
            if status in ("AWAITING_CONFIRMATION", "COMPLETED", "FAILED"):
                db.execute("UPDATE operations SET status=? WHERE operation_id=? AND attempt=?",
                           ("FAILED" if status == "FAILED" else "DONE", operation.operationId, operation.attempt))
            return event

    def pending_events(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM events WHERE delivered=0 ORDER BY operation_id,attempt,sequence LIMIT 100")]

    def delivered(self, event, *, discarded=False):
        with self.connect() as db:
            db.execute("UPDATE events SET delivered=? WHERE operation_id=? AND attempt=? AND sequence=?",
                       (2 if discarded else 1, event["operation_id"], event["attempt"], event["sequence"]))

    def trace(self, operation, name, elapsed_ms, result):
        with self.connect() as db:
            db.execute("INSERT INTO tool_traces(operation_id,attempt,name,elapsed_ms,result,created) VALUES(?,?,?,?,?,?)",
                       (operation.operationId, operation.attempt, name, elapsed_ms, encoded(result), time.time()))
