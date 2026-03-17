package com.codeexecution.controller;

import com.codeexecution.dto.request.AutosaveRequest;
import com.codeexecution.dto.request.CreateSessionRequest;
import com.codeexecution.dto.response.ExecutionResponse;
import com.codeexecution.dto.response.SessionResponse;
import com.codeexecution.service.CodeSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/code-sessions")
@RequiredArgsConstructor
@Tag(name = "Code Sessions", description = "Live coding session management APIs")
public class CodeSessionController {

    private final CodeSessionService codeSessionService;

    @PostMapping
    @Operation(
        summary = "Create a new live coding session",
        description = "Initializes a new session with a specified language and optional template code"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Session created successfully",
            content = @Content(schema = @Schema(implementation = SessionResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    public ResponseEntity<SessionResponse> createSession(
            @Valid @RequestBody CreateSessionRequest request) {
        SessionResponse response = codeSessionService.createSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{sessionId}")
    @Operation(
        summary = "Autosave source code",
        description = "Saves the learner's current code. Called frequently during live editing."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code saved successfully",
            content = @Content(schema = @Schema(implementation = SessionResponse.class))),
        @ApiResponse(responseCode = "404", description = "Session not found or not active"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<SessionResponse> autosave(
            @Parameter(description = "Session UUID", required = true)
            @PathVariable UUID sessionId,
            @Valid @RequestBody AutosaveRequest request) {
        SessionResponse response = codeSessionService.autosave(sessionId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{sessionId}/run")
    @Operation(
        summary = "Submit code for execution",
        description = "Enqueues the current session code for asynchronous execution. Returns immediately with a job ID."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Execution job queued",
            content = @Content(schema = @Schema(implementation = ExecutionResponse.class))),
        @ApiResponse(responseCode = "404", description = "Session not found"),
        @ApiResponse(responseCode = "400", description = "No source code to execute"),
        @ApiResponse(responseCode = "429", description = "Too many execution requests")
    })
    public ResponseEntity<ExecutionResponse> runCode(
            @Parameter(description = "Session UUID", required = true)
            @PathVariable UUID sessionId) {
        ExecutionResponse response = codeSessionService.submitRun(sessionId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
