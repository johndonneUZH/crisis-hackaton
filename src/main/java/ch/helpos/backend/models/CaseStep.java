package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class CaseStep {
    private String questionId;
    private String answer;
    private String notes;
    private List<String> attachmentIds;
    private Instant answeredAt;
}
