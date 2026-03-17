# Code Execution System

Secure backend for executing user-submitted code with async processing and Docker-based isolation.

## Architecture

```
Client → REST API (Spring Boot :8080)
                ↓
          Redis Queue
                ↓
     Execution Worker (Spring Boot :8081)
                ↓
     Docker-in-Docker Sandbox
     (fresh container per execution)
         ├── --network none
         ├── --memory 64m
         ├── --read-only filesystem
         ├── --user nobody
         └── --rm (auto-removed)
                ↓
        PostgreSQL (results)
```

## Quick Start

### Requirements
- Docker Engine 20.10+ & Docker Compose v2+
- Linux host (Docker socket must be accessible)

### Run

```bash
docker compose up --build
```

> First run pulls python:3.12-slim and node:20-slim sandbox images (~200MB).
> Subsequent runs are fast as images are cached.

### Swagger UI

```
http://localhost:8080/swagger-ui.html
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/code-sessions` | Create new coding session |
| PATCH | `/code-sessions/{id}` | Autosave source code |
| POST | `/code-sessions/{id}/run` | Submit code for async execution |
| GET | `/executions/{id}` | Poll for result |

## Example Flow

```bash
# 1. Create session
SESSION=$(curl -s -X POST http://localhost:8080/code-sessions \
  -H "Content-Type: application/json" \
  -d '{"language":"PYTHON","templateCode":"# start"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['sessionId'])")

# 2. Autosave code
curl -s -X PATCH http://localhost:8080/code-sessions/$SESSION \
  -H "Content-Type: application/json" \
  -d '{"language":"PYTHON","sourceCode":"for i in range(1,6):\n    print(f\"Line {i}\")"}'

# 3. Submit
EXEC=$(curl -s -X POST http://localhost:8080/code-sessions/$SESSION/run \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['executionId'])")

# 4. Poll (wait ~2-3s)
curl http://localhost:8080/executions/$EXEC
```

## Execution States

```
QUEUED -> RUNNING -> COMPLETED
                  -> FAILED   (worker error / max retries)
                  -> TIMEOUT  (exceeded 10s)
```

## Isolation (Docker-in-Docker)

Each execution runs in a fresh isolated container:

| Constraint | Value |
|-----------|-------|
| Network | --network none |
| Memory | --memory 64m --memory-swap 64m |
| CPU | --cpus 0.5 |
| Processes | --pids-limit 64 |
| Filesystem | --read-only (code bind-mounted ro, /tmp as tmpfs) |
| User | --user nobody |
| Privileges | --security-opt no-new-privileges |
| Lifetime | --rm auto-removed on exit |
| Timeout | 10s hard limit |

## Supported Languages

| Language | Image | Runtime |
|----------|-------|---------|
| PYTHON | python:3.12-slim | python3 -u |
| JAVASCRIPT | node:20-slim | node |

## Troubleshooting

**Execution stays QUEUED:**
```bash
docker compose logs worker --tail 30
```

**Docker permission denied in worker:**
The entrypoint auto-detects the docker socket GID and adds workeruser to it.
If this fails, check: ls -la /var/run/docker.sock

**First execution slow (5-10s):**
Normal on first run — Docker pulls python:3.12-slim. Pre-pulled by entrypoint.
