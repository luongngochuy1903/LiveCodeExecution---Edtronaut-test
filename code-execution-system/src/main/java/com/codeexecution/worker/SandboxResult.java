package com.codeexecution.worker;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SandboxResult {
    private int exitCode;
    private String stdout;
    private String stderr;
    private long executionTimeMs;
    private boolean timedOut;

    public static SandboxResult timeout(long elapsedMs) {
        return SandboxResult.builder()
                .exitCode(-1)
                .stdout("")
                .stderr("Execution timed out after " + elapsedMs + "ms")
                .executionTimeMs(elapsedMs)
                .timedOut(true)
                .build();
    }

    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }
}
