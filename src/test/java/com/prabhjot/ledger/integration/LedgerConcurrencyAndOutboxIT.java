package com.prabhjot.ledger.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prabhjot.ledger.application.service.AccountService;
import com.prabhjot.ledger.application.service.LedgerService;
import com.prabhjot.ledger.application.worker.OutboxRelayWorker;
import com.prabhjot.ledger.domain.model.Account;
import com.prabhjot.ledger.domain.model.EntryType;
import com.prabhjot.ledger.domain.model.OutboxEvent;
import com.prabhjot.ledger.domain.model.OutboxStatus;
import com.prabhjot.ledger.infrastructure.repository.AccountRepository;
import com.prabhjot.ledger.infrastructure.repository.LedgerTransactionRepository;
import com.prabhjot.ledger.infrastructure.repository.OutboxEventRepository;
import com.prabhjot.ledger.presentation.dto.AccountResponse;
import com.prabhjot.ledger.presentation.dto.CreateAccountRequest;
import com.prabhjot.ledger.presentation.dto.EntryItemRequest;
import com.prabhjot.ledger.presentation.dto.PostTransactionRequest;
import com.prabhjot.ledger.presentation.dto.TransactionResponse;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"ledger.events", "ledger.events.dlq"})
class LedgerConcurrencyAndOutboxIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelayWorker outboxRelayWorker;

    @SpyBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private io.awspring.cloud.sqs.operations.SqsTemplate sqsTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private final ConcurrentHashMap<String, String> redisStore = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisStore.clear();

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    String val = inv.getArgument(1);
                    return redisStore.putIfAbsent(key, val) == null;
                });

        when(valueOperations.get(anyString()))
                .thenAnswer(inv -> redisStore.get(inv.getArgument(0)));

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String val = inv.getArgument(1);
            redisStore.put(key, val);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> redisStore.remove(inv.getArgument(0)) != null);
    }

    @Test
    @DisplayName("Deadlock-free concurrent transfers with opposing lock acquisition order")
    void testDeadlockFreeConcurrentTransactions() throws Exception {
        // Create 2 accounts with initial balance 1000.0000 each
        AccountResponse accA = accountService.createAccount(new CreateAccountRequest("USD", new BigDecimal("1000.0000"), false));
        AccountResponse accB = accountService.createAccount(new CreateAccountRequest("USD", new BigDecimal("1000.0000"), false));

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Alternating transfer directions between Account A and Account B
                    UUID fromId = (index % 2 == 0) ? accA.id() : accB.id();
                    UUID toId = (index % 2 == 0) ? accB.id() : accA.id();

                    PostTransactionRequest req = new PostTransactionRequest(
                            "USD",
                            "Concurrent Transfer " + index,
                            List.of(
                                    new EntryItemRequest(fromId, EntryType.DEBIT, new BigDecimal("10.0000"), "USD"),
                                    new EntryItemRequest(toId, EntryType.CREDIT, new BigDecimal("10.0000"), "USD")
                            )
                    );

                    ledgerService.postTransaction("concurrent-key-" + UUID.randomUUID(), req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Deadlock would throw CannotAcquireLockException or DeadlockLoserDataAccessException
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // Verify total system zero-sum balance invariant: sum(A + B) must be exactly 2000.0000
        Account finalA = accountRepository.findById(accA.id()).orElseThrow();
        Account finalB = accountRepository.findById(accB.id()).orElseThrow();

        BigDecimal totalBalance = finalA.getBalance().add(finalB.getBalance());
        assertThat(totalBalance).isEqualByComparingTo(new BigDecimal("2000.0000"));
        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
    }

    @Test
    @DisplayName("Dual-layer idempotency prevents duplicate transaction execution")
    void testDualLayerIdempotency() throws Exception {
        AccountResponse accA = accountService.createAccount(new CreateAccountRequest("USD", new BigDecimal("100.0000"), false));
        AccountResponse accB = accountService.createAccount(new CreateAccountRequest("USD", new BigDecimal("0.0000"), false));

        String idempotencyKey = "idemp-" + UUID.randomUUID();

        PostTransactionRequest request = new PostTransactionRequest(
                "USD",
                "Idempotency Test Transfer",
                List.of(
                        new EntryItemRequest(accA.id(), EntryType.DEBIT, new BigDecimal("40.0000"), "USD"),
                        new EntryItemRequest(accB.id(), EntryType.CREDIT, new BigDecimal("40.0000"), "USD")
                )
        );

        String jsonPayload = objectMapper.writeValueAsString(request);

        // First Call: Expect 201 Created
        String firstResponseStr = mockMvc.perform(post("/api/v1/ledger/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey))
                .andReturn().getResponse().getContentAsString();

        TransactionResponse firstResponse = objectMapper.readValue(firstResponseStr, TransactionResponse.class);

        // Second Call with same Idempotency-Key: Expect 200 OK with identical transaction payload
        String secondResponseStr = mockMvc.perform(post("/api/v1/ledger/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey))
                .andReturn().getResponse().getContentAsString();

        TransactionResponse secondResponse = objectMapper.readValue(secondResponseStr, TransactionResponse.class);

        assertThat(firstResponse.id()).isEqualTo(secondResponse.id());

        // Verify account balances were debited only ONCE
        Account finalA = accountRepository.findById(accA.id()).orElseThrow();
        Account finalB = accountRepository.findById(accB.id()).orElseThrow();

        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("60.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("40.0000"));
    }

    @Test
    @DisplayName("Overdraft rule: InsufficientFundsException returns HTTP 422 when balance is insufficient")
    void testInsufficientFundsRejection() throws Exception {
        AccountResponse acc = accountService.createAccount(new CreateAccountRequest("USD", new BigDecimal("50.0000"), false));
        AccountResponse target = accountService.createAccount(new CreateAccountRequest("USD", new BigDecimal("0.0000"), false));

        PostTransactionRequest overdraftRequest = new PostTransactionRequest(
                "USD",
                "Overdraft Attempt",
                List.of(
                        new EntryItemRequest(acc.id(), EntryType.DEBIT, new BigDecimal("75.0000"), "USD"),
                        new EntryItemRequest(target.id(), EntryType.CREDIT, new BigDecimal("75.0000"), "USD")
                )
        );

        mockMvc.perform(post("/api/v1/ledger/transactions")
                        .header("Idempotency-Key", "overdraft-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overdraftRequest)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient funds")));

        // Verify account balance was unchanged
        Account currentAcc = accountRepository.findById(acc.id()).orElseThrow();
        assertThat(currentAcc.getBalance()).isEqualByComparingTo(new BigDecimal("50.0000"));
    }

    @Test
    @DisplayName("Transactional Outbox relay worker publishes pending events to Kafka and updates status")
    void testTransactionalOutboxRelay() throws Exception {
        AccountResponse accA = accountService.createAccount(new CreateAccountRequest("USD", new BigDecimal("200.0000"), false));
        AccountResponse accB = accountService.createAccount(new CreateAccountRequest("USD", new BigDecimal("100.0000"), false));

        PostTransactionRequest request = new PostTransactionRequest(
                "USD",
                "Outbox Test Transfer",
                List.of(
                        new EntryItemRequest(accA.id(), EntryType.DEBIT, new BigDecimal("25.0000"), "USD"),
                        new EntryItemRequest(accB.id(), EntryType.CREDIT, new BigDecimal("25.0000"), "USD")
                )
        );

        String idempKey = "outbox-test-" + UUID.randomUUID();
        TransactionResponse txResponse = ledgerService.postTransaction(idempKey, request);

        // Verify OutboxEvent was created in PENDING status
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        assertThat(pendingEvents).isNotEmpty();

        OutboxEvent targetEvent = pendingEvents.stream()
                .filter(e -> e.getAggregateId().equals(txResponse.id().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(targetEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // Run relay worker
        outboxRelayWorker.relayEvents();

        // Verify event updated to PUBLISHED
        OutboxEvent updatedEvent = outboxEventRepository.findById(targetEvent.getId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

        // Verify message received on Kafka topic ledger.events
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-outbox-group", "true", embeddedKafkaBroker);
        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer());
        try (Consumer<String, String> consumer = consumerFactory.createConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "ledger.events");
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, "ledger.events", Duration.ofSeconds(10));
            assertThat(record).isNotNull();
            assertThat(record.value()).contains(txResponse.id().toString());
        }
    }

    @Test
    @DisplayName("Outbox DLQ routing on repeated failure")
    void testOutboxDlqOnFailure() {
        OutboxEvent failedEvent = new OutboxEvent(
                UUID.randomUUID(),
                "LEDGER_TRANSACTION",
                UUID.randomUUID().toString(),
                "TRANSACTION_CREATED",
                "{\"test\":\"dlq-payload\"}",
                OutboxStatus.PENDING,
                2 // Already failed 2 times, next failure reaches 3
        );
        outboxEventRepository.save(failedEvent);

        // Mock Kafka failure when sending to ledger.events
        doAnswer(inv -> {
            throw new RuntimeException("Simulated Kafka Broker Connection Failure");
        }).when(kafkaTemplate).send(eq("ledger.events"), anyString(), anyString());

        // Run relay worker
        outboxRelayWorker.relayEvents();

        // Verify event status transitioned to FAILED with retry count 3
        OutboxEvent refreshed = outboxEventRepository.findById(failedEvent.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(refreshed.getRetryCount()).isEqualTo(3);

        // Verify DLQ message received on Kafka topic ledger.events.dlq
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-dlq-group", "true", embeddedKafkaBroker);
        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer());
        try (Consumer<String, String> consumer = consumerFactory.createConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "ledger.events.dlq");
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, "ledger.events.dlq", Duration.ofSeconds(10));
            assertThat(record).isNotNull();
            assertThat(record.value()).contains("dlq-payload");
        }
    }
}
