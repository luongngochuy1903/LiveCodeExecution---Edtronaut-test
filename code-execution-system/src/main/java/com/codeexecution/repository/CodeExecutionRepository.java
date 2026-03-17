package com.codeexecution.repository;

import com.codeexecution.domain.entity.CodeExecution;
import com.codeexecution.domain.enums.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface CodeExecutionRepository extends JpaRepository<CodeExecution, UUID> {

    List<CodeExecution> findBySessionId(UUID sessionId);

    @Query("SELECT e FROM CodeExecution e WHERE e.sessionId = :sessionId ORDER BY e.createdAt DESC LIMIT 1")
    java.util.Optional<CodeExecution> findLatestBySessionId(UUID sessionId);

    // For abuse detection: count executions by session in a time window
    long countBySessionIdAndCreatedAtAfter(UUID sessionId, LocalDateTime since);
}
