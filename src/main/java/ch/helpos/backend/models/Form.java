package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class Form {
    private String id;
    private String title;
    private String description;
    private String version;
    private String topicId;
    private String previousVersionId;
    private boolean active;
    private List<String> questionIds;
    private List<String> tags;
}

