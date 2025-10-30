package ch.helpos.backend.models;

import lombok.Data;
import java.util.List;

@Data
public class Form {
    private String id;
    private String title;
    private String description;
    private String version;
    private List<String> questions;
}


