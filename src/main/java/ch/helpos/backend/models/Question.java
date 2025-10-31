package ch.helpos.backend.models;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class Question {
    private String id;
    private String text;
    private String parentQuestionId;
    private String parentAnswerId;
    private String topicId;
    private String formId;
    private String source;
    private String answerType;
    private List<AnswerOption> answerOptions;
    private List<String> tags;
}
