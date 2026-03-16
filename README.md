# LiveCodeExecution---Edtronaut-test
Building and implementing logic structure and concept of real - time code execution in an isolated and controlled environment

# Code Execution System

## Table of Contents

1. [Introduction](#introduction)
2. [Diagram](#diagram)
3. [Architecture](#architecture)
   - [End-to-end Request Flow](#end-to-end-request-flow)
     - [Code Session Creation](#code-session-creation)
     - [Autosave Behavior](#autosave-behavior)
     - [Execution Request](#execution-request)
     - [Background Execution](#background-execution)
     - [Result Polling](#result-polling)
   - [Queue-based Execution Design](#queue-based-execution-design)
   - [Execution Lifecycle and State Management](#execution-lifecycle-and-state-management)
4. [Reliability & Data Model](#reliability--data-model)
   - [Execution States](#execution-states)
   - [Idempotency Handling](#idempotency-handling)
   - [Failure Handling](#failure-handling)
5. [Trade-offs](#trade-offs)
   - [Technology Choices and Why](#technology-choices-and-why)
   - [What You Optimized For](#what-you-optimized-for)
   - [Production Readiness Gaps](#production-readiness-gaps)

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

## Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  Client (React)                                                 │
│  • Creates session on load                                      │
│  • Autosaves every 12s                                          │
│  • Polls GET /executions/{id} for result                        │
└────────────────────┬────────────────────────────────────────────┘
                     │ HTTP REST
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│  REST API  (Spring Boot :8080)                                  │
│  • CodeSessionController  → CodeSessionService                  │
│  • CodeExecutionController → CodeExecutionService               │
│  • Swagger UI at /swagger-ui.html                               │
└──────────────┬──────────────────────────────────────────────────┘
               │ afterCommit() → LPUSH
               ▼
┌──────────────────────────┐
│  Redis  (queue)          │
│  Key: execution:queue    │
│  Type: List (LPUSH/LPOP) │
└──────────────┬───────────┘
               │ LPOP every 500ms
               ▼
┌─────────────────────────────────────────────────────────────────┐
│  Execution Worker  (Spring Boot :8081)                          │
│  • @Scheduled polls Redis                                       │
│  • Reads source code from PostgreSQL                            │
│  • Calls ProcessSandboxExecutor                                 │
└──────────────┬──────────────────────────────────────────────────┘
               │ docker run
               ▼
┌─────────────────────────────────────────────────────────────────┐
│  Docker Sandbox  (ephemeral container per execution)            │
│  --rm --network none --memory 64m --read-only --user nobody     │
│  python:3.12-slim  timeout 10 python3 -u /sandbox/main.py      │
└──────────────┬──────────────────────────────────────────────────┘
               │ stdout / stderr / exit code
               ▼
┌──────────────────────────┐
│  PostgreSQL              │
│  code_sessions           │
│  code_executions         │
└──────────────────────────┘
```

---

## Architecture

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
4. Registers a `TransactionSynchronization.afterCommit()` callback that pushes the `execution_id` to the Redis queue once the database transaction successfully commits.

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
5. Waits up to `timeoutSeconds + 5` seconds for the process to finish.
6. Maps the result to a terminal status (`COMPLETED`, `TIMEOUT`, or re-queues on transient failure).
7. Saves the final state, stdout, stderr, exit code, and `execution_time_ms` to the database.

The sandbox container is created with the following isolation constraints:

| Flag | Purpose |
|------|---------|
| `--rm` | Auto-remove container on exit |
| `--network none` | No internet access |
| `--memory 64m --memory-swap 64m` | Hard RAM limit, no swap |
| `--cpus 0.5` | Half a CPU core maximum |
| `--pids-limit 64` | Prevent fork bombs |
| `--read-only` | Immutable root filesystem |
| `--tmpfs /tmp:size=32m,noexec` | Writable but non-executable temp space |
| `--user nobody` | Non-root execution |
| `--security-opt no-new-privileges` | Block privilege escalation |

#### Result Polling

The client polls `GET /executions/{execution_id}` at a regular interval (1.5 seconds) until the response contains a terminal status (`COMPLETED`, `FAILED`, or `TIMEOUT`). The API reads the `CodeExecution` record from the database and returns the full result including `stdout`, `stderr`, `exit_code`, and `execution_time_ms`. Once a terminal status is received, the client stops polling.

---

### Queue-based Execution Design

The queue is a Redis **List** (`execution:queue`) used as a FIFO queue via `RPUSH` (enqueue) and `LPOP` (dequeue).

This design was chosen deliberately over message brokers (Kafka, RabbitMQ) for the following reasons:

- **Simplicity** — Redis is already required for caching and session data. Adding a queue costs zero additional infrastructure.
- **Sufficiency** — for a single-worker setup, a Redis list provides all necessary semantics: ordering, atomic pop, and persistence (via Redis RDB/AOF).
- **Observability** — queue depth is a single `LLEN execution:queue` command.

The worker does not use blocking `BLPOP`. Instead, it uses a `@Scheduled` method with a 500ms fixed delay. This is slightly less efficient than blocking pop (wastes one poll cycle when the queue is empty) but is simpler to reason about and integrates cleanly with Spring's scheduling model.

An important constraint of this design: **the job payload in Redis is only the `execution_id`**, not the full job data. The actual source code, language, and metadata are stored in PostgreSQL and fetched by the worker at processing time. This keeps Redis entries small and ensures the database is always the source of truth. If Redis is flushed, jobs that were in-flight can be recovered by scanning for `QUEUED` records in the database.

---

### Execution Lifecycle and State Management

Every execution record passes through a linear state machine:

```
                    ┌──────────────────────────────┐
                    │         QUEUED               │
                    │  Created by API after commit  │
                    └──────────────┬───────────────┘
                                   │ Worker picks up
                    ┌──────────────▼───────────────┐
                    │         RUNNING              │
                    │  Worker set started_at        │
                    └──────┬───────┬───────┬───────┘
                           │       │       │
              exit 0/non-0 │  OOM/ │ crash │ timeout
                    ┌──────▼──┐ ┌──▼───┐ ┌─▼──────┐
                    │COMPLETED│ │FAILED│ │TIMEOUT │
                    └─────────┘ └──────┘ └────────┘
                                    ▲
                             retry < 3
                          re-queue to QUEUED
```

State transitions are always persisted to the database before the next action. A worker that crashes between setting `RUNNING` and completing the execution will leave the record stuck in `RUNNING`. This is an acknowledged gap — see [Production Readiness Gaps](#production-readiness-gaps).

---

## Reliability & Data Model

### Execution States

| State | Description | Terminal |
|-------|------------|---------|
| `QUEUED` | Job created and pushed to Redis, awaiting a worker | No |
| `RUNNING` | Worker has picked up the job and started the sandbox container | No |
| `COMPLETED` | Execution finished within time and memory limits. `exit_code` may be non-zero | Yes |
| `FAILED` | Execution failed after exhausting all retries, or an unrecoverable error occurred | Yes |
| `TIMEOUT` | Execution exceeded the 10-second time limit | Yes |

`COMPLETED` does **not** mean the user's code was correct — it means the execution infrastructure ran the code successfully and captured its output. A Python `SyntaxError` produces `COMPLETED` with a non-zero `exit_code` and the error message in `stderr`.

---

### Idempotency Handling

#### Prevent duplicate execution runs

Each call to `POST /code-sessions/{id}/run` creates a **new** `CodeExecution` record with a fresh UUID. There is no deduplication based on code content — if the user clicks Run twice, two executions are created and both run. However, a rate limit of 10 executions per session per minute prevents abuse.

The Redis push only happens once per execution record, inside the `afterCommit()` callback. If the transaction rolls back (e.g. a constraint violation), the push never occurs and no orphaned job exists in Redis.

#### Safe reprocessing of jobs

When a worker retries a failed job, it re-queues the same `execution_id` with an incremented `retry_count`. The worker re-reads the source code from the database on each attempt. This is safe because:

- The source code stored in `code_executions` is a snapshot taken at submission time — it does not change even if the user continues editing.
- The worker guards against double-processing by checking `status == QUEUED` before proceeding. If the same `execution_id` is somehow popped twice, the second pop will find `status = RUNNING` and skip it.

---

### Failure Handling

#### Retries

Retries apply only to **worker-level failures** — exceptions thrown by the Java process itself (Docker daemon unreachable, temp file write error, unexpected crash). They do not apply to:

- User code that exits with a non-zero code → `COMPLETED` with `exit_code != 0`
- Timeouts → `TIMEOUT`
- OOM kills → `COMPLETED` with `exit_code = 137`

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
- `stderr` contains a timeout message with elapsed time
- `execution_time_ms` reflects actual elapsed time

#### Dead-letter or failed execution handling

There is no dedicated dead-letter queue in the current design. Executions that exhaust retries are marked `FAILED` in the database and remain there indefinitely. They can be:

- Queried by the client via `GET /executions/{id}`
- Inspected directly in PostgreSQL for debugging
- Re-submitted by the user by clicking Run again (which creates a new execution)

There is no automatic dead-letter reprocessing. If a failure is caused by a transient infrastructure problem (e.g. Docker daemon was temporarily down), the operator must either re-submit manually or write a one-off SQL update to re-queue affected records.

---

## Trade-offs

### Technology Choices and Why

| Component | Choice | Why |
|-----------|--------|-----|
| **API framework** | Spring Boot | Mature ecosystem, JPA/transaction management, `@Scheduled`, Swagger via springdoc — everything needed in one dependency set |
| **Database** | PostgreSQL | Strong transactional guarantees needed for the `afterCommit()` pattern. `QUEUED` records serve as a durable fallback if Redis is flushed |
| **Queue** | Redis List | Zero additional infrastructure. Sufficient for single-worker throughput. LPOP is atomic — no double-processing risk |
| **Sandbox** | Docker-in-Docker | Strongest isolation achievable without kernel changes. Each run gets a fresh container with hard resource limits. No language runtime needed in the worker image |
| **Execution timeout** | `timeout` command + Java `waitFor` | Two independent layers. `timeout` kills the user process, `waitFor` kills the Docker process. Belt and suspenders |
| **Stream reading** | Two threads (stdout + stderr) | Single-threaded reading blocks when one buffer fills. Concurrent reads prevent deadlock for programs with large mixed output |

### What You Optimized For

**Simplicity over throughput.** The system is designed to be understandable end-to-end by a single engineer. There are no distributed locks, no complex retry queues, no event sourcing. Every component does one thing and the interaction between components is explicit.

**Correctness over performance.** The `afterCommit()` pattern adds a small latency (the Redis push happens after the database round-trip) but eliminates an entire class of race conditions. The worker's 500ms polling interval adds up to half a second of queuing latency per job — acceptable for interactive use, not acceptable for sub-100ms SLAs.

**Isolation over speed.** Spinning up a Docker container per execution adds 500ms–2000ms overhead compared to running code in a pre-warmed process pool. This is a deliberate trade: full container isolation is worth the cold-start cost for untrusted user code.

### Production Readiness Gaps

The following gaps exist in the current implementation and would need to be addressed before a production deployment serving real users:

| Gap | Risk | Mitigation |
|-----|------|-----------|
| **Stuck RUNNING jobs** | A worker crash between `RUNNING` and `COMPLETED` leaves the record stuck indefinitely | Add a watchdog query: any execution in `RUNNING` for longer than `timeout + buffer` seconds is auto-transitioned to `FAILED` |
| **Single worker** | One worker processes one job at a time. A burst of submissions creates a queue backlog | Run multiple worker replicas. Redis `LPOP` is atomic — multiple workers can safely pop from the same list |
| **No authentication** | Any client can create sessions and submit code | Add Spring Security with JWT or session-based auth. Scope sessions to authenticated users |
| **No CSRF on backend** | Spring Security CSRF is not enabled in the current config | Enable `CsrfTokenRepository` with cookie-based token. Frontend is already wired to send `X-XSRF-TOKEN` |
| **Docker socket exposure** | Mounting `/var/run/docker.sock` gives the worker root-equivalent access to the host | Run Docker daemon in rootless mode, or use a dedicated container runtime socket with restricted permissions |
| **No image caching guarantee** | If `python:3.12-slim` is not cached, first execution is slow | Pre-pull images in the worker entrypoint (already implemented) and pin image digests to prevent silent updates |
| **Unbounded session storage** | Sessions and executions accumulate in the database forever | Add a TTL-based cleanup job: close `ACTIVE` sessions older than N hours, archive or delete old execution records |
| **No output streaming** | The client polls for the full result only after execution completes | Add WebSocket or SSE support to stream stdout in real time, line by line |
| **Hardcoded limits** | `timeout=10s`, `memory=64m`, `max-retries=3` are global | Make limits configurable per session or per language, stored in the session record |
| **No metrics or alerting** | Queue depth, worker lag, and execution error rates are not observable | Expose Micrometer metrics: queue depth gauge, execution duration histogram, error rate counter. Wire to Prometheus + Grafana |
