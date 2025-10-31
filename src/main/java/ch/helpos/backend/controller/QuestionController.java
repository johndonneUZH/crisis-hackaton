package ch.helpos.backend.controller;

import ch.helpos.backend.models.AnswerOption;
import ch.helpos.backend.models.Question;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.neo4j.driver.Driver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/topics/{topicId}/forms/{formId}/questions")
public class QuestionController extends AbstractController {

    public QuestionController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        super(mongoDatabase, neo4jDriver);
    }

    // --- Helpers ---
    private ObjectId parseObjectId(String id, String message) {
        try {
            return new ObjectId(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private Document requireForm(String topicId, String formId) {
        MongoCollection<Document> forms = mongoDatabase.getCollection("forms");
        Document formDoc = forms.find(new Document("_id", parseObjectId(formId, "Invalid form id")))
                .first();
        if (formDoc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Form not found");
        }
        if (!topicId.equals(formDoc.getString("topicId"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form does not belong to the provided topic");
        }
        if (!formDoc.getBoolean("active", true)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form is inactive and cannot accept new questions");
        }
        return formDoc;
    }

    @SuppressWarnings("unchecked")
    private Question mapDocToQuestion(Document doc) {
        List<String> subQuestionIds = doc.containsKey("subQuestionIds")
                ? (List<String>) doc.get("subQuestionIds")
                : Collections.emptyList();
        List<String> tags = doc.containsKey("tags")
                ? (List<String>) doc.get("tags")
                : Collections.emptyList();
        List<Document> optionsDocs = doc.containsKey("answerOptions")
                ? (List<Document>) doc.get("answerOptions")
                : Collections.emptyList();
        List<AnswerOption> answerOptions = mapDocToAnswerOptions(optionsDocs);

        return Question.builder()
                .id(doc.getObjectId("_id").toString())
                .text(doc.getString("text"))
                .subQuestionIds(subQuestionIds)
                .topicId(doc.getString("topicId"))
                .formId(doc.getString("formId"))
                .source(doc.getString("source"))
                .answerType(doc.getString("answerType"))
                .answerOptions(answerOptions)
                .tags(tags)
                .build();
    }

    private List<AnswerOption> mapDocToAnswerOptions(List<Document> optionDocs) {
        if (optionDocs == null || optionDocs.isEmpty()) {
            return Collections.emptyList();
        }
        List<AnswerOption> options = new ArrayList<>();
        for (Document optionDoc : optionDocs) {
            String optionId = optionDoc.getString("id");
            if (optionId == null || optionId.isBlank()) {
                optionId = UUID.randomUUID().toString();
            }
            options.add(AnswerOption.builder()
                    .id(optionId)
                    .label(optionDoc.getString("label"))
                    .nextQuestionId(optionDoc.getString("nextQuestionId"))
                    .legalReference(optionDoc.getString("legalReference"))
                    .terminal(optionDoc.getBoolean("terminal", false))
                    .build());
        }
        return options;
    }

    private List<Document> mapAnswerOptionsToDocs(List<AnswerOption> options) {
        if (options == null || options.isEmpty()) {
            return new ArrayList<>();
        }
        List<Document> docs = new ArrayList<>();
        for (AnswerOption option : options) {
            String optionId = option.getId() != null ? option.getId() : UUID.randomUUID().toString();
            docs.add(new Document("id", optionId)
                    .append("label", option.getLabel())
                    .append("nextQuestionId", option.getNextQuestionId())
                    .append("legalReference", option.getLegalReference())
                    .append("terminal", option.isTerminal()));
        }
        return docs;
    }

    // --- Create a top-level question ---
    @PostMapping
    public ResponseEntity<Question> createQuestion(@PathVariable String topicId,
                                                   @PathVariable String formId,
                                                   @RequestBody Question question) {
        requireForm(topicId, formId);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapDocToQuestion(saveQuestion(topicId, formId, null, question)));
    }

    // --- Create a subquestion under a parent question ---
    @PostMapping("/{parentQuestionId}")
    public ResponseEntity<Question> createSubQuestion(@PathVariable String topicId,
                                                      @PathVariable String formId,
                                                      @PathVariable String parentQuestionId,
                                                      @RequestBody Question question) {
        requireForm(topicId, formId);

        MongoCollection<Document> questions = mongoDatabase.getCollection("questions");
        ObjectId parentId;
        try {
            parentId = new ObjectId(parentQuestionId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid parent question id");
        }

        Document parent = questions.find(
                new Document("_id", parentId)
                        .append("formId", formId)
        ).first();
        if (parent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent question not found");
        }

        Document subQuestionDoc = saveQuestion(topicId, formId, parentQuestionId, question);
        String subQuestionId = subQuestionDoc.getObjectId("_id").toString();

        // Update parent question to include this subQuestionId
        questions.updateOne(
                new Document("_id", new ObjectId(parentQuestionId)),
                new Document("$addToSet", new Document("subQuestionIds", subQuestionId))
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(mapDocToQuestion(subQuestionDoc));
    }

    // --- Save question to MongoDB and Neo4j ---
    private Document saveQuestion(String topicId, String formId, String parentQuestionId, Question question) {
        MongoCollection<Document> forms = mongoDatabase.getCollection("forms");
        MongoCollection<Document> questions = mongoDatabase.getCollection("questions");

        Document doc = new Document("text", question.getText())
                .append("subQuestionIds", new ArrayList<String>()) // initially empty
                .append("source", question.getSource())
                .append("topicId", topicId)
                .append("formId", formId)
                .append("parentQuestionId", parentQuestionId)
                .append("answerType", question.getAnswerType())
                .append("answerOptions", mapAnswerOptionsToDocs(question.getAnswerOptions()))
                .append("tags", question.getTags() != null ? question.getTags() : new ArrayList<String>());

        questions.insertOne(doc);
        String id = doc.getObjectId("_id").toString();

        // --- Create node in Neo4j ---
        createNode("Question", id);

        // --- Link question to form ---
        createRelation("Question", id, "BELONGS_TO", "Form", formId);
        createRelation("Question", id, "BELONGS_TO", "Topic", topicId);

        // --- Link to parent question if exists ---
        if (parentQuestionId != null) {
            createRelation("Question", id, "SUBQUESTION_OF", "Question", parentQuestionId);
        }

        forms.updateOne(new Document("_id", parseObjectId(formId, "Invalid form id")),
                new Document("$addToSet", new Document("questionIds", id)));

        return doc;
    }

    // --- Get a single question by ID ---
    @GetMapping("/{questionId}")
    public ResponseEntity<Question> getQuestionById(@PathVariable String topicId,
                                                    @PathVariable String formId,
                                                    @PathVariable String questionId) {
        MongoCollection<Document> questions = mongoDatabase.getCollection("questions");
        Document doc;
        try {
            doc = questions.find(new Document("_id", new ObjectId(questionId))
                    .append("formId", formId))
                    .first();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid question id");
        }

        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found");
        }

        requireForm(topicId, formId);
        return ResponseEntity.ok(mapDocToQuestion(doc));
    }

    // --- Get all questions for a form ---
    @SuppressWarnings("unchecked")
    @GetMapping
    public ResponseEntity<List<Question>> getAllQuestions(@PathVariable String topicId,
                                                          @PathVariable String formId) {
        Document formDoc = requireForm(topicId, formId);

        MongoCollection<Document> questions = mongoDatabase.getCollection("questions");
        List<Question> result = new ArrayList<>();
        List<String> order = formDoc.containsKey("questionIds")
                ? (List<String>) formDoc.get("questionIds")
                : Collections.emptyList();

        List<Document> docs = questions.find(new Document("formId", formId)).into(new ArrayList<>());
        if (!order.isEmpty()) {
            Map<String, Integer> orderIndex = new HashMap<>();
            for (int i = 0; i < order.size(); i++) {
                orderIndex.put(order.get(i), i);
            }
            docs.sort((left, right) -> {
                String leftId = left.getObjectId("_id").toString();
                String rightId = right.getObjectId("_id").toString();
                int leftIdx = orderIndex.getOrDefault(leftId, Integer.MAX_VALUE);
                int rightIdx = orderIndex.getOrDefault(rightId, Integer.MAX_VALUE);
                return Integer.compare(leftIdx, rightIdx);
            });
        }
        for (Document doc : docs) {
            result.add(mapDocToQuestion(doc));
        }
        return ResponseEntity.ok(result);
    }
}
