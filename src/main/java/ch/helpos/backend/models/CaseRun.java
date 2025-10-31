package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class CaseRun {
    private String id;
    private String caseId;
    private String topicId;
    private String formId;
    private String profileId;
    private String lawyerId;
    private String status;
    private Boolean extended;
    private String outcome;
    private String closureNotes;
    private List<CaseStep> steps;
    private List<String> answeredQuestionIds;
    private List<String> tags;
    private List<String> attachmentIds;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
}
