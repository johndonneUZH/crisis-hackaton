package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AnswerOption {
    private String id;
    private String label;
    private String nextQuestionId;
    private String legalReference;
    private boolean terminal;
}
