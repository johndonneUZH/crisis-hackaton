package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Profile {
    private String id;
    private String name;
    private String biologicalSex;
    private String dateOfBirth;
    private String countryRequested;
}
