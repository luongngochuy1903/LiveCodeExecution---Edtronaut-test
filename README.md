# LiveCodeExecution---Edtronaut-test
Building and implementing logic structure and concept of real - time code execution in an isolated and controlled environment

## Table of Contents

1. [Introduction](#introduction)
2. [Database Design](#database-design)
3. [Diagram](#diagram)
4. [Architecture](#architecture)
   - [End-to-end Request Flow](#end-to-end-request-flow)
     - [Code Session Creation](#code-session-creation)
     - [Autosave Behavior](#autosave-behavior)
     - [Execution Request](#execution-request)
     - [Background Execution](#background-execution)
     - [Result Polling](#result-polling)
   - [Queue-based Execution Design](#queue-based-execution-design)
   - [Execution Lifecycle and State Management](#execution-lifecycle-and-state-management)
5. [API document](#api-document)
6. [Reliability & Data Model](#reliability--data-model)
   - [Execution States](#execution-states)
   - [Idempotency Handling](#idempotency-handling)
   - [Failure Handling](#failure-handling)
7. [Trade-offs](#trade-offs)
   - [Technology Choices and Why](#technology-choices-and-why)
   - [What You Optimized For](#optimized-this-over-that)
   - [Production Readiness Gaps](#production-readiness-gaps)
8. [How to run](#how-to-run)
9. [Demo](#demo)

---

## Introduction

The **Code Execution System** is a secure, asynchronous backend for executing user-submitted code in isolated environments. Users write code in a live coding session, submit it for execution, and receive the output — without the API ever blocking on the actual code run.

The system is designed around three core principles:

- **Security** — every piece of user code runs in a fully isolated Docker container that is created fresh and destroyed immediately after execution. No shared state between runs, no network access, no privilege escalation.
- **Reliability** — execution jobs are persisted to a database before being queued. If a worker crashes mid-execution, the job can be retried. If a job is never picked up, its state remains in the database and is recoverable.
- **Simplicity** — the architecture deliberately avoids heavy infrastructure. A single Redis list acts as the job queue. A scheduled worker polls and processes one job at a time. There are no message brokers, no complex distributed state machines, no external orchestration layers.

### Supported languages

| Language | Runtime | Container image |
|----------|---------|----------------|
| Python | `python3 -u` | `python:3.12-slim` |
| JavaScript | `node` | `node:20-slim` |

---

## Database Design

### Schema

Two tables only — intentionally minimal.
<img width="551" height="420" alt="class_diagram" src="https://github.com/user-attachments/assets/bbee1904-7d08-4467-9e6d-6dc5d8f21a5f" />

**`code_sessions`**

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID PK | Session identifier |
| `language` | ENUM | PYTHON, JAVASCRIPT |
| `source_code` | TEXT | Current code (updated on autosave) |
| `template_code` | TEXT | Initial template, never changes |
| `status` | ENUM | ACTIVE, CLOSED |
| `created_at` | TIMESTAMP | Session creation time |
| `updated_at` | TIMESTAMP | Last autosave time |

**`code_executions`**

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID PK | Execution identifier |
| `session_id` | UUID FK | Reference to code_sessions |
| `language` | ENUM | Snapshot of language at submit time |
| `source_code` | TEXT | Snapshot of code at submit time |
| `status` | ENUM | QUEUED, RUNNING, COMPLETED, FAILED, TIMEOUT |
| `stdout` | TEXT | Captured standard output (max 64KB) |
| `stderr` | TEXT | Captured standard error (max 16KB) |
| `exit_code` | INT | Process exit code (0=success, 124=timeout, 137=OOM) |
| `execution_time_ms` | BIGINT | Wall clock duration |
| `retry_count` | INT | Number of worker retries attempted |
| `queued_at` | TIMESTAMP | When pushed to Redis queue |
| `started_at` | TIMESTAMP | When worker picked up the job |
| `completed_at` | TIMESTAMP | When execution reached terminal state |
| `created_at` | TIMESTAMP | Record creation time |
| `updated_at` | TIMESTAMP | Last status update time |

### Design decisions

**code_sessions**

Represents an active coding session for a user. Tracks the live state of the code in real time. The session lifecycle begins when the user opens the editor and ends when the session is closed.  

**code_executions**

Represents a single code execution attempt. Each time the user clicks Run, a new record is created. This table is the communication layer between the API and the Worker along with the full job lifecycle from QUEUED to COMPLETED OR ERROR state.

**Why `source_code` is stored in both tables**

`code_sessions.source_code` is the live code — updated on every autosave. `code_executions.source_code` is a snapshot taken at submit time and never changes. This ensures the worker always executes the exact version the user submitted, even if the user continues editing after clicking Run.

**Why stdout/stderr are stored in the database**

Simplicity — no external object storage dependency. The 64KB/16KB truncation limits prevent the table from growing unbounded. A production system would store output in S3 and keep only the URL in the database.

**Why UUIDs instead of auto-increment IDs**

UUIDs are safe to expose in URLs and API responses without leaking sequential information about how many sessions or executions exist.

**DB as the communication layer between API and Worker**

API and Worker never talk directly. The database is the only shared state:
- API writes a `QUEUED` record → pushes execution ID to Redis
- Worker reads source code from DB → updates status as it progresses
- API reads final status/output from DB → returns to client
This means if either service restarts mid-execution, no data is lost.

---

## Diagram
- *Sequence Diagram*

<img width="1809" height="2144" alt="business_sequence" src="https://github.com/user-attachments/assets/299c2061-cf99-426a-b2ae-d355857fd787" />


---

## Architecture
<img width="1441" height="1011" alt="architecture (1)" src="https://github.com/user-attachments/assets/e0d8e042-df8c-4349-9280-fb1ff9842812" />

### End-to-end Request Flow

#### Code Session Creation

When the client application loads, it immediately calls `POST /code-sessions` with a chosen language and an optional template code snippet. The API creates a `CodeSession` record in PostgreSQL with `status = ACTIVE` and returns a `session_id`. All subsequent operations in the session reference this ID. The session acts as a container for the user's code state — it holds the most recently saved `source_code` and the chosen `language`.

```
Client                      API                     PostgreSQL
  │                          │                           │
  │  POST /code-sessions     │                           │
  │  {language, templateCode}│                           │
  │─────────────────────────►│                           │
  │                          │  INSERT code_sessions     │
  │                          │──────────────────────────►│
  │                          │  ◄── session record       │
  │  {sessionId, ACTIVE}     │                           │
  │◄─────────────────────────│                           │
```

#### Autosave Behavior

The client calls `PATCH /code-sessions/{session_id}` with the current source code. This is called automatically by the frontend every 12 seconds if the code has changed, and also on demand when the user clicks the Save button manually. The API updates the `source_code` field on the session record and returns the updated session status. No execution is triggered — this is a pure persistence operation.

The autosave endpoint is designed to be called at high frequency without side effects. It is idempotent: calling it twice with the same code produces the same result as calling it once.

```
Client                      API                     PostgreSQL
  │                          │                           │
  │  PATCH /code-sessions/   │                           │
  │  {id}                    │                           │
  │  {language, sourceCode}  │                           │
  │─────────────────────────►│                           │
  │                          │  UPDATE code_sessions     │
  │                          │  SET source_code = ?      │
  │                          │──────────────────────────►│
  │  {sessionId, ACTIVE}     │                           │
  │◄─────────────────────────│                           │
```

#### Execution Request

When the user clicks Run, the client calls `POST /code-sessions/{session_id}/run`. The API performs several steps within a single database transaction:

1. Validates the session is `ACTIVE` and has non-empty source code.
2. Checks a rate limit: no more than 10 execution requests per session per minute.
3. Creates a `CodeExecution` record with `status = QUEUED` and persists it.
4. Registers a callback that pushes the `execution_id` to the Redis queue once the database transaction successfully commits.

The key design decision here is that the Redis push happens **after** the database commit, not inside the transaction. This prevents a race condition where the worker picks up the job from Redis before the database record is visible, which would result in a "not found" error.

The API returns `202 Accepted` immediately with the `execution_id` and `status = QUEUED`. The client does not wait for the code to run.

```
Client            API                  PostgreSQL           Redis
  │                │                       │                  │
  │  POST /run     │                       │                  │
  │───────────────►│                       │                  │
  │                │  BEGIN TX             │                  │
  │                │  INSERT execution     │                  │
  │                │  (status=QUEUED)      │                  │
  │                │──────────────────────►│                  │
  │                │  COMMIT TX            │                  │
  │                │──── afterCommit() ────────────────────── ►│
  │                │                       │   LPUSH {id}     │
  │  202 QUEUED    │                       │                  │
  │◄───────────────│                       │                  │
```

#### Background Execution

The `ExecutionWorker` runs in a separate Spring Boot process (the `ces-worker` container). It polls the Redis list every 500ms using `LPOP`. When a job is found:

1. Loads the `CodeExecution` record from the database.
2. Updates `status = RUNNING`, sets `started_at = now()`.
3. Calls `ProcessSandboxExecutor.execute()`, which writes the source code to a temp file, then invokes `docker run` with strict isolation flags.
4. Reads stdout and stderr concurrently using two threads to prevent output buffer deadlock.
5. Maps the result to a terminal status (`COMPLETED`, `TIMEOUT`, or re-queues on transient failure).
6. Saves the final state, stdout, stderr, exit code, and `execution_time_ms` to the database.

#### Time limit, memory limit and isolated
To protect our worker node from mallicious and dangerous scripts from typing input.
The sandbox container is created with the following isolation constraints (demo, could be change at anytime). We limit memory used to 64MB, maximum of CPU core and isolate container so that there is no system-call scripts could be executed.

| Flag | Purpose |
|------|---------|
| `--rm` | Auto-remove container on exit |
| `--network none` | No internet access |
| `--memory 64m --memory-swap 64m` | Hard RAM limit, no swap |
| `--cpus 0.5` | Half a CPU core maximum |
| `--pids-limit 64` | Prevent fork bombs |
| `--read-only` | Immutable root filesystem |

<img width="688" height="715" alt="image" src="https://github.com/user-attachments/assets/1de04243-4d65-4bcf-be3a-b13d454a41d0" />

**SIGTERM**  — a signal to require the process to stop.
**SIGKILL** - In case of SIGTERM could be catched and ignored by code, SIGKILL appears to kill kernel immediatelly.

#### Result Polling

The client polls `GET /executions/{execution_id}` at a regular interval (1.5 seconds) until the response contains a terminal status (`COMPLETED`, `FAILED`, or `TIMEOUT`). The API reads the `CodeExecution` record from the database and returns the full result including `stdout`, `stderr`, `exit_code`, and `execution_time_ms`. Once a terminal status is received, the client stops polling.

---

### Queue-based Execution Design

The queue is a Redis **List** (`execution:queue`) used as a FIFO queue via `RPUSH` (enqueue) and `LPOP` (dequeue).

This design was chosen over message brokers (Kafka, RabbitMQ) for the following reasons:

- **Simplicity** — Redis is already required for caching and session data. Adding a queue costs zero additional infrastructure.
- **Sufficiency** — for a single worker setup, a Redis list provides all necessities: ordering, atomic pop.

The worker uses a `@Scheduled` method with a 500ms fixed delay. This is slightly less efficient than blocking pop (wastes one poll cycle when the queue is empty) but is simpler to reason about.

An important constraint of this design: **the job payload in Redis is only the `execution_id`**, not the full job data. The actual source code, language, and metadata are stored in PostgreSQL and fetched by the worker at processing time. This keeps Redis entries small and ensures the database is always the source of truth.

<img width="899" height="502" alt="queue" src="https://github.com/user-attachments/assets/adf7d9de-729e-48b6-9542-e015ed58e6c8" />

---

### Execution Lifecycle and State Management

Every execution record passes through a linear state machine:

```
                    ┌──────────────────────────────┐
                    │         QUEUED               │
                    │  Created by API after commit  │
                    └──────────────┬───────────────┘
                                   │ Worker started to execute
                    ┌──────────────▼───────────────┐
                    │         RUNNING              │
                    │  Worker set started_at        │
                    └──────┬───────┬───────┬───────┘
                           │       │       │
                   exit 0/ │  OOM  │ crash │ timeout
                    ┌──────▼──┐ ┌──▼───┐ ┌─▼──────┐
                    │COMPLETED│ │FAILED│ │TIMEOUT │
                    └─────────┘ └──────┘ └────────┘
                                    ▲
                             retry < 3
                          re-queue to QUEUED
```

State transitions are always persisted to the database before the next action. A worker that crashes between setting `RUNNING` and completing the execution will leave the record stuck in `RUNNING`.

To monitor a specific lifecycle, it is wise to log every status transforming step to terminal.

---

## API document
https://luongngochuy1903.github.io/LiveCodeExecution---Edtronaut-test/

## Reliability & Data Model

### Execution States

| State | Description | Terminal |
|-------|------------|---------|
| `QUEUED` | Job created and pushed to Redis, awaiting a worker | No |
| `RUNNING` | Worker has picked up the job and started the sandbox container | No |
| `COMPLETED` | Execution finished within time and memory limits. `exit_code` may be non-zero | Yes |
| `FAILED` | Execution failed after exhausting all retries, or an unrecoverable error occurred | Yes |
| `TIMEOUT` | Execution exceeded the 10-second time limit | Yes |

---

### Idempotency Handling

#### Prevent duplicate execution runs

Each call to `POST /code-sessions/{id}/run` creates a new `CodeExecution` record with a fresh UUID. There is no duplication based on code content, if the user clicks Run twice, two executions are created and both run. However, a rate limit of 10 executions per session per minute prevents abuse.

The Redis push only happens once per execution record, inside the `afterCommit()` callback. If the transaction rolls back (processing to Postgres somehow corrupted), the push never occurs and no orphaned job exists in Redis.

#### Safe reprocessing of jobs

When a worker retries a failed job, it re-queues the same `execution_id` with an incremented `retry_count`. The worker re-reads the source code from the database on each attempt. This is safe because:

- The source code stored in `code_executions` is a snapshot taken at submission time — it does not change even if the user continues editing.
- The worker guards against double-processing by checking `status == QUEUED` before proceeding. If the same `execution_id` is somehow popped twice, the second pop will find `status = RUNNING` and skip it.

---

### Failure Handling

#### Retries

Retries apply only to **worker-level failures** — exceptions thrown by the Java process itself (Docker daemon unreachable, temp file write error, unexpected crash).
The retry logic is in `ExecutionWorker.handleFailure()`:
```
retry_count < max_retries (3)
    → increment retry_count
    → set status = QUEUED
    → RPUSH execution_id back to Redis

retry_count >= max_retries
    → set status = FAILED
    → store error detail in stderr field
```

The error message stored in `stderr` includes the full Java exception chain so the caller can diagnose the failure without reading server logs.

#### Error states

A `FAILED` execution has the following fields populated:

- `status = FAILED`
- `stderr` contains the worker error message and exception chain
- `completed_at` is set
- `stdout` and `exit_code` may be null

A `TIMEOUT` execution has:

- `status = TIMEOUT`
- `stderr` contains a timeout message
- `execution_time_ms` reflects actual elapsed time
---

## Trade-offs

### Technology Choices and Why

| Component | Choice | Why |
|-----------|--------|-----|
| **API framework** | Spring Boot | JPA/transaction management, `@Scheduled`, Everything is integrated with Spring framework, which include every tech stack used in this project |
| **Database** | PostgreSQL | Strong transactional guarantees needed for the `afterCommit()` pattern. `QUEUED` records serve as a durable fallback if Redis is flushed |
| **Queue** | Redis List | popular infrastructure for storing key-value object. Sufficient for one worker throughput. LPOP is atomic — no double-processing risk |
| **Sandbox** | Docker-in-Docker | Easy way to build without implementing any external tools. Each run gets a fresh container. No language runtime needed in the worker image and easily edited in code |

### Optimized This Over That

**Simplicity over throughput.** The system is designed to be understandable end-to-end by a single engineer. There are no distributed locks, no complex retry queues, no event sourcing. Every component does one thing and the interaction between components is explicit.

**Correctness over performance.** The `afterCommit()` pattern adds a small latency (the Redis push happens after the database round-trip) but eliminates an entire class of race conditions. The worker's 500ms polling interval adds up to half a second of queuing latency per job — acceptable for interactive use, not acceptable for sub-100ms SLAs.

**Isolation over speed.** Spinning up a Docker container per execution adds 500ms–2000ms overhead compared to running code in a pre-warmed process pool. This is a deliberate trade: full container isolation is worth the cold-start cost for untrusted user code.

### Production Readiness Gaps
| Gap | Risk | Mitigation |
|-----|------|-----------|
| **Stuck RUNNING jobs** | A worker crash between `RUNNING` and `COMPLETED` leaves the record stuck indefinitely | Add a watchdog query: any execution in `RUNNING` for longer than period of time is auto-transitioned to `FAILED` |
| **Single worker** | One worker processes one job at a time. A burst of submissions creates a queue backlog | Run multiple worker replicas. Redis `LPOP` is atomic — multiple workers can safely pop from the same list |
| **Docker socket exposure** | Mounting `/var/run/docker.sock` gives the worker root-equivalent access to the host | Run Docker daemon in rootless mode, or use a dedicated container runtime socket with restricted permissions |
| **No image caching guarantee** | `python:3.12-slim` is not cached, first execution is slow | Pre-pull images in the worker entrypoint (already implemented) and pin image digests to prevent silent updates |
| **Unbounded session storage** | Sessions and executions accumulate in the database forever | Add a TTL-based cleanup job: Archive or delete old execution records |
| **Hardcoded limits** | `timeout=10s`, `memory=64m`, `max-retries=3` are global | Make limits configurable per session or per language, stored in the session record |

---

## How to run

```bash
# Pull and move to the project first
git pull https://github.com/luongngochuy1903/LiveCodeExecution---Edtronaut-test.git

cd LiveCodeExecution---Edtronaut-test

# Move to backend source
cd code-execution-system

# Start Docker container up to booting backend server.
# Note that the first time built may illustrate errors in ces-worker and ces-api container because of waiting time of these 2 servers for database server might be exceed. The solution is waiting for ces-redis and ces-postgres healthy, then restart ces-worker and ces-api
docker-compose up --build -d

# ces-api: container for main web api server.
# ces-worker: container for external worker to consume job from Redis queue.
# ces-redis: container hosted Redis based-queue - implement message-queue pattern.
# ces-postgres: container for Postgres database - this is the source of truth.

# Now web api server is running on 8080, worker is running on 8081, check api with http://localhost:8080/swagger-ui.html

#For interacting with UI, comeback to code-editor-frontend folder
cd ..
cd code-editor-frontend

# Run installing npm packages
npm install

# Run project, server is running on http://localhost:3000/
npm run dev

```

---
## Demo
The basic UI when first opening code editor:
<img width="1919" height="869" alt="image" src="https://github.com/user-attachments/assets/2785591e-3b37-4b24-95c8-2c55199b8b25" />

Assume that we are going to run a Python script. Let see the result:
<img width="1919" height="869" alt="image" src="https://github.com/user-attachments/assets/0dc63f5b-5723-4367-964f-b3140d52fccc" />

