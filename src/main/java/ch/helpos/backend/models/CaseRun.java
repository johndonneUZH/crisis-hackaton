package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Builder
@Data
@Document(collection = "runs")
public class CaseRun {
    @Id
    private String id;
    private String profileId;
    private String formId;
    private String topicId;
    private List<CaseStep> steps;
    private String outcome;
    private String status; // e.g., "RUNNING", "COMPLETED"
    private Instant startedAt;
    private Instant closedAt;
}
