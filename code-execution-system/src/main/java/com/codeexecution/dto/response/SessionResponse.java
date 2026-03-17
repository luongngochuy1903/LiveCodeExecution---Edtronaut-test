package com.codeexecution.dto.response;

import com.codeexecution.domain.enums.Language;
import com.codeexecution.domain.enums.SessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Code session response")
public class SessionResponse {

    @Schema(description = "Session UUID")
    private UUID sessionId;

    @Schema(description = "Current status", example = "ACTIVE")
    private SessionStatus status;

    @Schema(description = "Programming language", example = "PYTHON")
    private Language language;

    @Schema(description = "Session creation timestamp")
    private LocalDateTime createdAt;
}
