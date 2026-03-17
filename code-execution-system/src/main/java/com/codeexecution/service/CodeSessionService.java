package com.codeexecution.service;

import com.codeexecution.domain.entity.CodeExecution;
import com.codeexecution.domain.entity.CodeSession;
import com.codeexecution.domain.enums.ExecutionStatus;
import com.codeexecution.domain.enums.SessionStatus;
import com.codeexecution.dto.request.AutosaveRequest;
import com.codeexecution.dto.request.CreateSessionRequest;
import com.codeexecution.dto.response.ExecutionResponse;
import com.codeexecution.dto.response.SessionResponse;
import com.codeexecution.exception.RateLimitException;
import com.codeexecution.exception.ResourceNotFoundException;
import com.codeexecution.repository.CodeExecutionRepository;
import com.codeexecution.repository.CodeSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeSessionService {

    private final CodeSessionRepository sessionRepository;
    private final CodeExecutionRepository executionRepository;
    private final RedisQueueService redisQueueService;

    @Value("${app.execution.max-retries}")
    private int maxRetries;

    @Transactional
    public SessionResponse createSession(CreateSessionRequest request) {
        CodeSession session = CodeSession.builder()
                .language(request.getLanguage())
                .templateCode(request.getTemplateCode())
                .sourceCode(request.getTemplateCode())
                .status(SessionStatus.ACTIVE)
                .build();

        session = sessionRepository.save(session);
        log.info("[SESSION] Created session: sessionId={}, language={}", session.getId(), session.getLanguage());

        return toSessionResponse(session);
    }

    @Transactional
    public SessionResponse autosave(UUID sessionId, AutosaveRequest request) {
        CodeSession session = findActiveSession(sessionId);

        session.setSourceCode(request.getSourceCode());
        session.setLanguage(request.getLanguage());
        session = sessionRepository.save(session);

        log.debug("[SESSION] Autosaved: sessionId={}, codeLength={}", sessionId, request.getSourceCode().length());
        return toSessionResponse(session);
    }

    @Transactional
    public ExecutionResponse submitRun(UUID sessionId) {
        CodeSession session = findActiveSession(sessionId);

        // Abuse prevention: max 10 executions per session in 1 minute
        long recentCount = executionRepository.countBySessionIdAndCreatedAtAfter(
                sessionId, LocalDateTime.now().minusMinutes(1));
        if (recentCount >= 10) {
            log.warn("[SESSION] Rate limit hit: sessionId={}, recentCount={}", sessionId, recentCount);
            throw new RateLimitException("Too many execution requests. Please wait before running again.");
        }

        if (session.getSourceCode() == null || session.getSourceCode().isBlank()) {
            throw new IllegalArgumentException("No source code to execute. Please write some code first.");
        }

        CodeExecution execution = CodeExecution.builder()
                .sessionId(sessionId)
                .language(session.getLanguage())
                .sourceCode(session.getSourceCode())
                .status(ExecutionStatus.QUEUED)
                .queuedAt(LocalDateTime.now())
                .build();

        execution = executionRepository.save(execution);

        // CRITICAL: push to Redis AFTER the transaction commits to DB.
        // If we push inside the transaction, the worker may poll and query DB
        // before the record is visible, causing "Execution not found" error.
        final UUID executionId = execution.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisQueueService.pushJob(executionId);
                log.info("[SESSION] Job pushed to queue after commit: executionId={}", executionId);
            }
        });

        log.info("[SESSION] Submitted run: sessionId={}, executionId={}", sessionId, execution.getId());

        return ExecutionResponse.builder()
                .executionId(execution.getId())
                .sessionId(sessionId)
                .status(ExecutionStatus.QUEUED)
                .queuedAt(execution.getQueuedAt())
                .build();
    }

    private CodeSession findActiveSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .filter(s -> s.getStatus() == SessionStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found or not active: " + sessionId));
    }

    private SessionResponse toSessionResponse(CodeSession session) {
        return SessionResponse.builder()
                .sessionId(session.getId())
                .status(session.getStatus())
                .language(session.getLanguage())
                .createdAt(session.getCreatedAt())
                .build();
    }
}