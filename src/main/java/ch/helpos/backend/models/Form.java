package ch.helpos.backend.models;

import lombok.Data;
import lombok.Builder;
import java.util.List;

@Data
@Builder
public class Form {
    private String id;
    private String title;
    private String description;
    private String version;
    private List<String> questions;
    private List<String> tags;
}


