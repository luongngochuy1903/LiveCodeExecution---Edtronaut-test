package com.codeexecution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisQueueService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.execution.queue-key}")
    private String queueKey;

    public void pushJob(UUID executionId) {
        try {
            redisTemplate.opsForList().rightPush(queueKey, executionId.toString());
            log.info("[QUEUE] Pushed execution job: executionId={}", executionId);
        } catch (Exception e) {
            log.error("[QUEUE] Failed to push job: executionId={}", executionId, e);
            throw new RuntimeException("Failed to enqueue execution job", e);
        }
    }

    public String popJob(long timeoutSeconds) {
        try {
            return redisTemplate.opsForList().leftPop(queueKey);
        //     return redisTemplate.opsForList()
        // .leftPop(queueKey, Duration.ofSeconds(timeoutSeconds));
        } catch (Exception e) {
            log.error("[QUEUE] Failed to pop job from queue", e);
            return null;
        }
    }

    public long queueSize() {
        Long size = redisTemplate.opsForList().size(queueKey);
        return size != null ? size : 0;
    }
}
