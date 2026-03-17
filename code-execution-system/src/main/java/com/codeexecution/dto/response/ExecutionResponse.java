package com.codeexecution.dto.response;

import com.codeexecution.domain.enums.ExecutionStatus;
import com.codeexecution.domain.enums.Language;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Code execution response")
public class ExecutionResponse {

    @Schema(description = "Execution UUID")
    private UUID executionId;

    @Schema(description = "Associated session UUID")
    private UUID sessionId;

    @Schema(description = "Execution status", example = "COMPLETED")
    private ExecutionStatus status;

    @Schema(description = "Standard output", example = "Hello World\n")
    private String stdout;

    @Schema(description = "Standard error output", example = "")
    private String stderr;

    @Schema(description = "Execution duration in milliseconds", example = "120")
    private Long executionTimeMs;

    @Schema(description = "Exit code", example = "0")
    private Integer exitCode;

    @Schema(description = "Programming language")
    private Language language;

    @Schema(description = "Timestamp when queued")
    private LocalDateTime queuedAt;

    @Schema(description = "Timestamp when execution started")
    private LocalDateTime startedAt;

    @Schema(description = "Timestamp when execution completed")
    private LocalDateTime completedAt;
}
