package ch.helpos.backend.models;

import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class Topic {
    private String id;
    private String name;
    private String description;
}
