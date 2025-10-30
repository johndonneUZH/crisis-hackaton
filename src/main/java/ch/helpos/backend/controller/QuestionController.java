package ch.helpos.backend.controller;

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
import java.util.List;

@RestController
@RequestMapping("/topics/{topicId}/forms/{formId}/questions")
public class QuestionController extends AbstractController {

    public QuestionController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        super(mongoDatabase, neo4jDriver);
    }

    // --- Helper method: map MongoDB Document to Question ---
    @SuppressWarnings("unchecked")
    private Question mapDocToQuestion(Document doc) {
        return Question.builder()
                .id(doc.getObjectId("_id").toString())
                .text(doc.getString("text"))
                .subQuestionIds((List<String>) doc.get("subQuestionIds"))
                .answers((List<String>) doc.get("answers"))
                .formId(doc.getString("formId"))
                .source(doc.getString("source"))
                .build();
    }

    // --- Create a top-level question ---
    @PostMapping
    public ResponseEntity<Question> createQuestion(@PathVariable String topicId,
                                                   @PathVariable String formId,
                                                   @RequestBody Question question) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mapDocToQuestion(saveQuestion(formId, null, question)));
    }

    // --- Create a subquestion under a parent question ---
    @PostMapping("/{parentQuestionId}")
    public ResponseEntity<Question> createSubQuestion(@PathVariable String topicId,
                                                      @PathVariable String formId,
                                                      @PathVariable String parentQuestionId,
                                                      @RequestBody Question question) {
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

        Document subQuestionDoc = saveQuestion(formId, parentQuestionId, question);
        String subQuestionId = subQuestionDoc.getObjectId("_id").toString();

        // Update parent question to include this subQuestionId
        questions.updateOne(
                new Document("_id", new ObjectId(parentQuestionId)),
                new Document("$push", new Document("subQuestionIds", subQuestionId))
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(mapDocToQuestion(subQuestionDoc));
    }

    // --- Save question to MongoDB and Neo4j ---
    private Document saveQuestion(String formId, String parentQuestionId, Question question) {
        MongoCollection<Document> questions = mongoDatabase.getCollection("questions");

        Document doc = new Document("text", question.getText())
                .append("subQuestionIds", new ArrayList<String>()) // initially empty
                .append("answers", question.getAnswers())
                .append("source", question.getSource())
                .append("formId", formId)
                .append("parentQuestionId", parentQuestionId);

        questions.insertOne(doc);
        String id = doc.getObjectId("_id").toString();

        // --- Create node in Neo4j ---
        createNode("Question", id);

        // --- Link question to form ---
        createRelation("Question", id, "BELONGS_TO", "Form", formId);

        // --- Link to parent question if exists ---
        if (parentQuestionId != null) {
            createRelation("Question", id, "SUBQUESTION_OF", "Question", parentQuestionId);
        }

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

        return ResponseEntity.ok(mapDocToQuestion(doc));
    }

    // --- Get all questions for a form ---
    @GetMapping
    public ResponseEntity<List<Question>> getAllQuestions(@PathVariable String topicId,
                                                          @PathVariable String formId) {
        MongoCollection<Document> questions = mongoDatabase.getCollection("questions");
        List<Question> result = new ArrayList<>();
        for (Document doc : questions.find(new Document("formId", formId))) {
            result.add(mapDocToQuestion(doc));
        }
        return ResponseEntity.ok(result);
    }
}
