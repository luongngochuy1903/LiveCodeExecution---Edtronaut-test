package com.codeexecution.dto.request;

import com.codeexecution.domain.enums.Language;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request to autosave current source code")
public class AutosaveRequest {

    @NotNull(message = "Language is required")
    @Schema(description = "Programming language", example = "PYTHON")
    private Language language;

    @NotBlank(message = "Source code cannot be blank")
    @Schema(description = "Current source code", example = "print('Hello World')")
    private String sourceCode;
}
