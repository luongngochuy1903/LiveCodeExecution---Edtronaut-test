package com.codeexecution.controller;

import com.codeexecution.dto.response.ExecutionResponse;
import com.codeexecution.service.CodeExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/executions")
@RequiredArgsConstructor
@Tag(name = "Code Executions", description = "Code execution status and result retrieval APIs")
public class CodeExecutionController {

    private final CodeExecutionService codeExecutionService;

    @GetMapping("/{executionId}")
    @Operation(
        summary = "Get execution status and result",
        description = "Poll this endpoint to check execution progress. States: QUEUED → RUNNING → COMPLETED | FAILED | TIMEOUT"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Execution found",
            content = @Content(schema = @Schema(implementation = ExecutionResponse.class))),
        @ApiResponse(responseCode = "404", description = "Execution not found")
    })
    public ResponseEntity<ExecutionResponse> getExecution(
            @Parameter(description = "Execution UUID returned from /run", required = true)
            @PathVariable UUID executionId) {
        ExecutionResponse response = codeExecutionService.getExecution(executionId);
        return ResponseEntity.ok(response);
    }
}
