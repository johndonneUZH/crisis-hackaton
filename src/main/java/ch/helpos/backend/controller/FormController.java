package ch.helpos.backend.controller;

import ch.helpos.backend.models.Form;
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
@RequestMapping("/topics/{topicId}/forms")
public class FormController extends AbstractController {

    public FormController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        super(mongoDatabase, neo4jDriver);
    }

    // --- Helper: map MongoDB Document to Form ---
    @SuppressWarnings("unchecked")
    private Form mapDocToForm(Document doc) {
        return Form.builder()
                .id(doc.getObjectId("_id").toString())
                .title(doc.getString("title"))
                .description(doc.getString("description"))
                .version(doc.getString("version"))
                .questions((List<String>) doc.get("questions"))
                .tags((List<String>) doc.get("tags"))
                .build();
    }

    // --- Create a form ---
    @PostMapping
    public ResponseEntity<Form> createForm(@PathVariable String topicId, @RequestBody Form form) {
        MongoCollection<Document> forms = mongoDatabase.getCollection("forms");

        Document doc = new Document("title", form.getTitle())
                .append("description", form.getDescription())
                .append("version", form.getVersion())
                .append("questions", form.getQuestions())
                .append("tags", form.getTags())
                .append("topicId", topicId); // link to topic

        forms.insertOne(doc);
        String formId = doc.getObjectId("_id").toString();

        // --- Neo4j ---
        createNode("Form", formId);
        createRelation("Form", formId, "BELONGS_TO", "Topic", topicId);

        return ResponseEntity.status(HttpStatus.CREATED).body(mapDocToForm(doc));
    }

    // --- Get a form by ID ---
    @GetMapping("/{formId}")
    public ResponseEntity<Form> getFormById(@PathVariable String topicId, @PathVariable String formId) {
        MongoCollection<Document> forms = mongoDatabase.getCollection("forms");
        Document doc;
        try {
            doc = forms.find(new Document("_id", new ObjectId(formId))
                    .append("topicId", topicId))
                    .first();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid form id");
        }

        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Form not found");
        }

        return ResponseEntity.ok(mapDocToForm(doc));
    }

    // --- Get all forms under a topic ---
    @GetMapping
    public ResponseEntity<List<Form>> getAllForms(@PathVariable String topicId) {
        MongoCollection<Document> forms = mongoDatabase.getCollection("forms");
        List<Form> result = new ArrayList<>();

        for (Document doc : forms.find(new Document("topicId", topicId))) {
            result.add(mapDocToForm(doc));
        }

        return ResponseEntity.ok(result);
    }
}
