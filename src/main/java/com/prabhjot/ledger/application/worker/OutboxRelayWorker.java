package com.prabhjot.ledger.application.worker;

import com.prabhjot.ledger.domain.model.OutboxEvent;
import com.prabhjot.ledger.domain.model.OutboxStatus;
import com.prabhjot.ledger.infrastructure.repository.OutboxEventRepository;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SqsTemplate sqsTemplate;

    @Value("${ledger.kafka.topics.events:ledger.events}")
    private String eventsTopic;

    @Value("${ledger.kafka.topics.dlq:ledger.events.dlq}")
    private String dlqTopic;

    @Value("${ledger.sqs.queue-name:ledger-audit-queue}")
    private String sqsQueueName;

    public OutboxRelayWorker(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Autowired(required = false) SqsTemplate sqsTemplate
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.sqsTemplate = sqsTemplate;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval-ms:1000}")
    public void relayEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox events to relay", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                // Publish to Kafka topic ledger.events
                kafkaTemplate.send(eventsTopic, event.getAggregateId(), event.getPayload()).get(5, TimeUnit.SECONDS);

                // Publish to AWS SQS ledger-audit-queue (skips cleanly if unreachable)
                if (sqsTemplate != null) {
                    try {
                        sqsTemplate.send(to -> to.queue(sqsQueueName).payload(event.getPayload()));
                    } catch (Exception sqsEx) {
                        log.warn("SQS delivery failed or endpoint unreachable for event {}, continuing: {}",
                                event.getId(), sqsEx.getMessage());
                    }
                }

                // On success, mark as PUBLISHED
                event.setStatus(OutboxStatus.PUBLISHED);
                outboxEventRepository.save(event);
                log.info("Outbox event {} successfully published to Kafka", event.getId());
            } catch (Exception ex) {
                log.error("Failed to relay outbox event {}: {}", event.getId(), ex.getMessage());
                event.incrementRetryCount();

                if (event.getRetryCount() >= 3) {
                    try {
                        kafkaTemplate.send(dlqTopic, event.getAggregateId(), event.getPayload()).get(5, TimeUnit.SECONDS);
                        log.warn("Outbox event {} reached max retries and sent to DLQ topic {}", event.getId(), dlqTopic);
                    } catch (Exception dlqEx) {
                        log.error("Failed to send event {} to DLQ topic {}", event.getId(), dlqTopic, dlqEx);
                    }
                    event.setStatus(OutboxStatus.FAILED);
                }

                outboxEventRepository.save(event);
            }
        }
    }
}
