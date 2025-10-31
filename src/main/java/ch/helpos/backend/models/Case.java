package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class Case {
    private String id;
    private String title;
    private String description;
    private String status;
    private String topicId;
    private String formId;
    private String formVersion;
    private String profileId;
    private boolean extended;
    private String outcome;
    private String closureNotes;
    private List<CaseStep> steps;
    private List<String> answeredQuestionIds;
    private List<String> tags;
    private List<String> attachmentIds;
    private Instant createdAt;
    private Instant completedAt;
}
