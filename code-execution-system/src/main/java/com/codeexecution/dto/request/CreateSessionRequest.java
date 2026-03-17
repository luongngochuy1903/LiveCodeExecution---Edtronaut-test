package com.codeexecution.dto.request;

import com.codeexecution.domain.enums.Language;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request to create a new coding session")
public class CreateSessionRequest {

    @NotNull(message = "Language is required")
    @Schema(description = "Programming language", example = "PYTHON")
    private Language language;

    @Schema(description = "Initial template code", example = "# Write your code here\n")
    private String templateCode;
}
