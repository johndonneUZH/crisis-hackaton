package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class CaseRunAggregation {
    private String id;
    private String topicId;
    private String formId;
    private String caseId;
    private List<String> answerSignature;
    private String outcome;
    private int usefulCount;
    private String lastRunId;
    private Instant createdAt;
    private Instant lastMatchedAt;
}
