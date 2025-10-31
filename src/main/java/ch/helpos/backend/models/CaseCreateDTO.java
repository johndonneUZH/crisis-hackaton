package ch.helpos.backend.models;

import lombok.Data;

@Data    
public class CaseCreateDTO {
    private String title;
    private String description;
    private String profileId;
}
