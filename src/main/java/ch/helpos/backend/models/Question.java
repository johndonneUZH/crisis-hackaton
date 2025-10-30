package ch.helpos.backend.models;

import lombok.Data;
import java.util.List;

@Data
public class Question {
    private String id;
    private String text;
    private List<String> subQuestions;
    private List<String> answers;
    private String source;
}