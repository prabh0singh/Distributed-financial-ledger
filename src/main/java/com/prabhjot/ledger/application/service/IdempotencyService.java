package com.prabhjot.ledger.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prabhjot.ledger.presentation.dto.TransactionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String IDEMPOTENCY_PREFIX = "idemp:";
    private static final String PROCESSING_STATUS = "PROCESSING";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean acquireInFlightLock(String idempotencyKey) {
        String key = IDEMPOTENCY_PREFIX + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, PROCESSING_STATUS, TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public Optional<TransactionResponse> getCachedResponse(String idempotencyKey) {
        String key = IDEMPOTENCY_PREFIX + idempotencyKey;
        String val = redisTemplate.opsForValue().get(key);
        if (val == null || PROCESSING_STATUS.equals(val)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(val, TransactionResponse.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached TransactionResponse for key: {}", idempotencyKey, e);
            return Optional.empty();
        }
    }

    public void cacheResponse(String idempotencyKey, TransactionResponse response) {
        String key = IDEMPOTENCY_PREFIX + idempotencyKey;
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize TransactionResponse for key: {}", idempotencyKey, e);
        }
    }

    public void releaseLock(String idempotencyKey) {
        String key = IDEMPOTENCY_PREFIX + idempotencyKey;
        redisTemplate.delete(key);
    }
}
