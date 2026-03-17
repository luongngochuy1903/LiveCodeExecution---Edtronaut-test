package com.codeexecution.worker;

import com.codeexecution.domain.enums.Language;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Docker-in-Docker sandbox executor.
 *
 * Each execution spins up a fresh, isolated Docker container:
 *   - Separate filesystem, PID namespace, network namespace
 *   - Memory + CPU hard limits enforced by Docker
 *   - No network access (--network none)
 *   - Read-only filesystem except /tmp
 *   - Runs as non-root inside the container (--user nobody)
 *   - Container is auto-removed after execution (--rm)
 *
 * Requires: /var/run/docker.sock mounted into the worker container.
 */
@Slf4j
@Component
public class ProcessSandboxExecutor {

    @Value("${app.execution.timeout-seconds}")
    private int timeoutSeconds;

    @Value("${app.execution.memory-limit-mb}")
    private int memoryLimitMb;

    @Value("${app.execution.temp-dir}")
    private String tempDir;

    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            Path base = Paths.get(tempDir);
            Files.createDirectories(base);
            // Ensure world-writable so any uid inside container can write
            base.toFile().setWritable(true, false);
            base.toFile().setReadable(true, false);
            base.toFile().setExecutable(true, false);
            log.info("[SANDBOX] Temp dir ready: {}", base.toAbsolutePath());
        } catch (Exception e) {
            log.error("[SANDBOX] Failed to create temp dir: {}", tempDir, e);
        }

        // Verify docker is accessible
        try {
            Process check = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
            String version = new String(check.getInputStream().readAllBytes()).trim();
            boolean ok = check.waitFor(5, TimeUnit.SECONDS);
            if (ok && !version.isBlank()) {
                log.info("[SANDBOX] Docker available, version: {}", version);
            } else {
                log.warn("[SANDBOX] Docker not accessible — sandbox will fail. Check /var/run/docker.sock mount.");
            }
        } catch (Exception e) {
            log.warn("[SANDBOX] Docker check failed: {}", e.getMessage());
        }
    }

    public SandboxResult execute(UUID executionId, Language language, String sourceCode) throws Exception {
        log.debug("[SANDBOX] Starting DinD execution: executionId={}, language={}, codeLength={}",
                executionId, language, sourceCode.length());

        // Write code to a temp file on the host (worker container) filesystem
        // This file will be bind-mounted (read-only) into the sandbox container
        Path workDir = createWorkDir(executionId);
        try {
            Path sourceFile = writeSourceFile(workDir, language, sourceCode);
            return runInDockerContainer(executionId, language, sourceFile, workDir);
        } finally {
            cleanupWorkDir(workDir);
        }
    }

    // ─── Docker container execution ─────────────────────────────────────────

    private SandboxResult runInDockerContainer(UUID executionId, Language language,
                                                Path sourceFile, Path workDir) throws Exception {
        String containerName = "sandbox-" + executionId;
        List<String> command = buildDockerCommand(containerName, language, sourceFile, workDir);

        log.info("[SANDBOX] Launching container: {}", containerName);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        long startTime = System.currentTimeMillis();
        Process process = pb.start();

        StringWriter stdoutWriter = new StringWriter();
        StringWriter stderrWriter = new StringWriter();

        // Read stdout/stderr concurrently to prevent buffer deadlock
        Thread stdoutThread = new Thread(() -> drainStream(process.getInputStream(), stdoutWriter, 65536));
        Thread stderrThread = new Thread(() -> drainStream(process.getErrorStream(), stderrWriter, 16384));
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();

        // Wait with outer timeout (Docker --stop-timeout handles inner, this is belt+suspenders)
        boolean finished = process.waitFor(timeoutSeconds + 5L, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            forceKillContainer(containerName);
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("[SANDBOX] Outer timeout hit: executionId={}, elapsedMs={}", executionId, elapsed);
            return SandboxResult.timeout(elapsed);
        }

        stdoutThread.join(3000);
        stderrThread.join(3000);

        long executionTimeMs = System.currentTimeMillis() - startTime;
        int exitCode = process.exitValue();

        String stdout = stdoutWriter.toString();
        String stderr  = stderrWriter.toString();

        // Docker timeout exit code = 124 (from `timeout` command inside)
        // Docker OOM kill = 137
        if (exitCode == 124) {
            log.warn("[SANDBOX] Container timed out (exit 124): executionId={}", executionId);
            return SandboxResult.timeout(executionTimeMs);
        }

        if (exitCode == 137) {
            log.warn("[SANDBOX] Container OOM killed (exit 137): executionId={}", executionId);
            stderr = "Process killed: exceeded memory limit (" + memoryLimitMb + "MB)\n" + stderr;
        }

        log.info("[SANDBOX] Container done: executionId={}, exitCode={}, timeMs={}", executionId, exitCode, executionTimeMs);

        return SandboxResult.builder()
                .exitCode(exitCode)
                .stdout(truncate(stdout, 65536))
                .stderr(truncate(stderr, 16384))
                .executionTimeMs(executionTimeMs)
                .timedOut(false)
                .build();
    }

    private List<String> buildDockerCommand(String containerName, Language language,
                                             Path sourceFile, Path workDir) {
        // The source file is on the worker container filesystem.
        // We bind-mount the workDir into the sandbox container as read-only.
        String hostWorkDir = workDir.toAbsolutePath().toString();
        String containerWorkDir = "/sandbox";
        String containerSourceFile = containerWorkDir + "/" + sourceFile.getFileName();

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");                                          // auto-remove on exit
        cmd.add("--name"); cmd.add(containerName);
        cmd.add("--network"); cmd.add("none");                   // no network
        cmd.add("--memory"); cmd.add(memoryLimitMb + "m");       // memory limit
        cmd.add("--memory-swap"); cmd.add(memoryLimitMb + "m");  // disable swap
        cmd.add("--cpus"); cmd.add("0.5");                       // half a CPU core
        cmd.add("--pids-limit"); cmd.add("64");                  // max 64 processes
        cmd.add("--read-only");                                   // read-only root fs
        cmd.add("--tmpfs"); cmd.add("/tmp:size=32m,noexec");     // writable /tmp, no exec
        cmd.add("--user"); cmd.add("nobody");                    // non-root
        cmd.add("--security-opt"); cmd.add("no-new-privileges"); // no privilege escalation
        cmd.add("-v"); cmd.add(hostWorkDir + ":" + containerWorkDir + ":ro"); // mount code read-only
        cmd.add("--stop-timeout"); cmd.add(String.valueOf(timeoutSeconds));
        
//         0–10s — code chạy bình thường, không ai can thiệp.
// 10s — timeout command (chạy bên trong container) gửi SIGTERM — tín hiệu "hãy tự dừng lại đi". Hầu hết process sẽ dừng tại đây. Exit code = 124.
// 12s — nếu process vẫn còn sống (ví dụ code Python bắt signal và bỏ qua), timeout gửi SIGKILL — tín hiệu này không thể bị bắt hay bỏ qua, OS buộc kill ngay lập tức.
// 15s — nếu cả hai trên thất bại (ví dụ docker run process bị treo), Java waitFor(15s) hết hạn, gọi process.destroyForcibly() + docker rm -f để kill container từ bên ngoài.

        // Select image and run command based on language
        switch (language) {
            case PYTHON -> {
                cmd.add("python:3.12-slim");
                cmd.add("timeout");
                cmd.add("--kill-after=2");
                cmd.add(String.valueOf(timeoutSeconds)); //SIGTERM sau 10s, SIGKILL sau 12s
                cmd.add("python3");
                cmd.add("-u");
                cmd.add(containerSourceFile);
            }
            case JAVASCRIPT -> {
                cmd.add("node:20-slim");
                cmd.add("timeout");
                cmd.add("--kill-after=2"); // +2s SIGKILL nếu SIGTERM bị ignore
                cmd.add(String.valueOf(timeoutSeconds));
                cmd.add("node");
                cmd.add("--max-old-space-size=" + memoryLimitMb);
                cmd.add(containerSourceFile);
            }
            default -> throw new UnsupportedOperationException("Unsupported language: " + language);
        }

        return cmd;
    }

    private void forceKillContainer(String containerName) {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
            log.info("[SANDBOX] Force-removed container: {}", containerName);
        } catch (Exception e) {
            log.warn("[SANDBOX] Failed to force-remove container {}: {}", containerName, e.getMessage());
        }
    }

    // ─── Filesystem helpers ─────────────────────────────────────────────────

    private Path createWorkDir(UUID executionId) throws Exception {
        Path dir = Paths.get(tempDir, executionId.toString());
        Files.createDirectories(dir);
        // Make world-readable so Docker bind-mount can read as 'nobody'
        dir.toFile().setReadable(true, false);
        dir.toFile().setExecutable(true, false);
        return dir;
    }

    private Path writeSourceFile(Path workDir, Language language, String sourceCode) throws Exception {
        String filename = "main" + language.getExtension();
        Path file = workDir.resolve(filename);
        Files.writeString(file, sourceCode, StandardCharsets.UTF_8);
        file.toFile().setReadable(true, false); // world-readable for 'nobody' user in container
        return file;
    }

    private void cleanupWorkDir(Path workDir) {
        try {
            if (Files.exists(workDir)) {
                Files.walk(workDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Exception e) {
            log.warn("[SANDBOX] Failed to cleanup workdir: {}", workDir, e);
        }
    }

    // ─── Stream & util helpers ───────────────────────────────────────────────

    private void drainStream(java.io.InputStream input, StringWriter writer, int maxBytes) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            int totalRead = 0;
            String line;
            while ((line = reader.readLine()) != null && totalRead < maxBytes) {
                writer.write(line);
                writer.write("\n");
                totalRead += line.length() + 1;
            }
        } catch (Exception e) {
            log.debug("[SANDBOX] Stream drain closed", e);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "\n[Output truncated]" : s;
    }
}
