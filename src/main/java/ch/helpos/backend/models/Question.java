package ch.helpos.backend.models;

import lombok.Data;
import lombok.Builder;
import java.util.List;

@Data
@Builder
public class Question {
    private String id;
    private String text;
    private List<String> subQuestionIds;
    private List<String> answers;
    private String formId;
    private String source;
}