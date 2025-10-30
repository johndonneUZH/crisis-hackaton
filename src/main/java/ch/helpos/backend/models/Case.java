package ch.helpos.backend.models;

import lombok.Data;
import lombok.Builder;
import java.util.List;

@Data
@Builder
public class Case {
    private String id;
    private String title;
    private String description;
    private String status;
    private String parentForm;
    private List<String> tags;
}