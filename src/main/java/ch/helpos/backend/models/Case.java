package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Builder
@Data
@Document(collection = "cases")
public class Case {
    @Id
    private String id;
    private String title;
    private String description;
    private String status; // e.g., "OPEN", "CLOSED"
    private String topicId;
    private String formId;
    private String formVersion;
    private String profileId; 
    private String outcome;
    private String closureNotes;
    private Instant createdAt;
    private Instant completedAt;
    private List<CaseStep> steps;
    private List<String> answeredQuestionIds;
    private int frequency; 
}
