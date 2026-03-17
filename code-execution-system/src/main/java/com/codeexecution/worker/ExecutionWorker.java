package com.codeexecution.worker;

import com.codeexecution.domain.entity.CodeExecution;
import com.codeexecution.domain.enums.ExecutionStatus;
import com.codeexecution.repository.CodeExecutionRepository;
import com.codeexecution.service.RedisQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionWorker {

    private final RedisQueueService redisQueueService;
    private final CodeExecutionRepository executionRepository;
    private final ProcessSandboxExecutor sandboxExecutor;

    @Value("${app.execution.max-retries}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${app.worker.poll-interval-ms:500}")
    public void pollAndExecute() {
        String jobId = redisQueueService.popJob(1);
        if (jobId == null) return;

        try {
            UUID executionId = UUID.fromString(jobId);
            processExecution(executionId);
        } catch (IllegalArgumentException e) {
            log.error("[WORKER] Invalid job id in queue: {}", jobId);
        }
    }

    @Transactional
    public void processExecution(UUID executionId) {
        Optional<CodeExecution> opt = executionRepository.findById(executionId);
        if (opt.isEmpty()) {
            log.warn("[WORKER] Execution not found in DB, skipping: executionId={}", executionId);
            return;
        }

        CodeExecution execution = opt.get();

        // Guard: only process QUEUED executions
        if (execution.getStatus() != ExecutionStatus.QUEUED) {
            log.warn("[WORKER] Execution is not QUEUED, skipping: executionId={}, status={}", executionId, execution.getStatus());
            return;
        }

        // Mark as RUNNING
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setStartedAt(LocalDateTime.now());
        executionRepository.save(execution);
        log.info("[WORKER] QUEUED -> RUNNING: executionId={}, language={}", executionId, execution.getLanguage());

        try {
            SandboxResult result = sandboxExecutor.execute(executionId, execution.getLanguage(), execution.getSourceCode());
            applyResult(execution, result);
        } catch (Exception e) {
            handleFailure(execution, e);
        }
    }

    private void applyResult(CodeExecution execution, SandboxResult result) {
        if (result.isTimedOut()) {
            execution.setStatus(ExecutionStatus.TIMEOUT);
            log.warn("[WORKER] RUNNING -> TIMEOUT: executionId={}", execution.getId());
        } else {
            execution.setStatus(ExecutionStatus.COMPLETED);
            log.info("[WORKER] RUNNING -> COMPLETED: executionId={}, exitCode={}, timeMs={}",
                    execution.getId(), result.getExitCode(), result.getExecutionTimeMs());
        }

        execution.setStdout(result.getStdout());
        execution.setStderr(result.getStderr());
        execution.setExitCode(result.getExitCode());
        execution.setExecutionTimeMs(result.getExecutionTimeMs());
        execution.setCompletedAt(LocalDateTime.now());

        executionRepository.save(execution);
    }

    private void handleFailure(CodeExecution execution, Exception e) {
        int retries = execution.getRetryCount();
        log.error("[WORKER] Execution error: executionId={}, retryCount={}", execution.getId(), retries, e);

        String errorDetail = buildErrorDetail(e);

        if (retries < maxRetries) {
            execution.setRetryCount(retries + 1);
            execution.setStatus(ExecutionStatus.QUEUED);
            execution.setStartedAt(null);
            execution.setStderr("Retry " + (retries + 1) + "/" + maxRetries + " - last error: " + errorDetail);
            executionRepository.save(execution);
            redisQueueService.pushJob(execution.getId());
            log.info("[WORKER] Re-queued for retry: executionId={}, attempt={}/{}", execution.getId(), retries + 1, maxRetries);
        } else {
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setStderr("Worker failed after " + retries + " retries.\n" + errorDetail);
            execution.setCompletedAt(LocalDateTime.now());
            executionRepository.save(execution);
            log.error("[WORKER] RUNNING -> FAILED (max retries exceeded): executionId={}", execution.getId());
        }
    }

    private String buildErrorDetail(Exception e) {
        StringBuilder sb = new StringBuilder();
        Throwable cause = e;
        while (cause != null) {
            sb.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
            if (cause != null) sb.append("\nCaused by: ");
        }
        return sb.toString();
    }
}
