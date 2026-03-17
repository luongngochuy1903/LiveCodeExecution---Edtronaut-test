package com.codeexecution.service;

import com.codeexecution.domain.entity.CodeExecution;
import com.codeexecution.dto.response.ExecutionResponse;
import com.codeexecution.exception.ResourceNotFoundException;
import com.codeexecution.repository.CodeExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeExecutionService {

    private final CodeExecutionRepository executionRepository;

    public ExecutionResponse getExecution(UUID executionId) {
        CodeExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found: " + executionId));

        return toExecutionResponse(execution);
    }

    public ExecutionResponse toExecutionResponse(CodeExecution execution) {
        return ExecutionResponse.builder()
                .executionId(execution.getId())
                .sessionId(execution.getSessionId())
                .status(execution.getStatus())
                .stdout(execution.getStdout())
                .stderr(execution.getStderr())
                .executionTimeMs(execution.getExecutionTimeMs())
                .exitCode(execution.getExitCode())
                .language(execution.getLanguage())
                .queuedAt(execution.getQueuedAt())
                .startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt())
                .build();
    }
}
