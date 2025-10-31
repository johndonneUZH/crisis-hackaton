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
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/topics/{topicId}/forms")
public class FormController extends AbstractController {

    public FormController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        super(mongoDatabase, neo4jDriver);
    }

    // --- Helpers ---
    private void ensureTopicExists(String topicId) {
        MongoCollection<Document> topics = mongoDatabase.getCollection("topics");
        Document topic = topics.find(new Document("_id", parseObjectId(topicId, "Invalid topic id"))).first();
        if (topic == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Topic not found");
        }
    }

    private ObjectId parseObjectId(String id, String errorMessage) {
        try {
            return new ObjectId(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }

    @SuppressWarnings("unchecked")
    private Form mapDocToForm(Document doc) {
        List<String> questionIds = doc.containsKey("questionIds")
                ? (List<String>) doc.get("questionIds")
                : Collections.emptyList();
        List<String> tags = doc.containsKey("tags")
                ? (List<String>) doc.get("tags")
                : Collections.emptyList();

        return Form.builder()
                .id(doc.getObjectId("_id").toString())
                .title(doc.getString("title"))
                .description(doc.getString("description"))
                .version(doc.getString("version"))
                .topicId(doc.getString("topicId"))
                .previousVersionId(doc.getString("previousVersionId"))
                .active(doc.getBoolean("active", true))
                .questionIds(questionIds)
                .tags(tags)
                .build();
    }

    // --- Create a form or a new form version ---
    @PostMapping
    public ResponseEntity<Form> createForm(@PathVariable String topicId, @RequestBody Form form) {
        ensureTopicExists(topicId);

        MongoCollection<Document> forms = mongoDatabase.getCollection("forms");
        List<String> questionIds = form.getQuestionIds() != null ? form.getQuestionIds() : new ArrayList<>();
        List<String> tags = form.getTags() != null ? form.getTags() : new ArrayList<>();

        String previousVersionId = form.getPreviousVersionId();
        if (previousVersionId != null) {
            ObjectId previousObjectId = parseObjectId(previousVersionId, "Invalid previous form id");
            Document previous = forms.find(new Document("_id", previousObjectId))
                    .first();
            if (previous == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Previous form version not found");
            }
            if (!topicId.equals(previous.getString("topicId"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Previous form does not belong to this topic");
            }
            // Mark older version as inactive
            forms.updateOne(new Document("_id", previousObjectId),
                    new Document("$set", new Document("active", false)));
        }

        Document doc = new Document("title", form.getTitle())
                .append("description", form.getDescription())
                .append("version", form.getVersion())
                .append("topicId", topicId)
                .append("previousVersionId", previousVersionId)
                .append("active", true)
                .append("questionIds", questionIds)
                .append("tags", tags);

        forms.insertOne(doc);
        String formId = doc.getObjectId("_id").toString();

        // --- Neo4j ---
        createNode("Form", formId);
        createRelation("Form", formId, "BELONGS_TO", "Topic", topicId);
        if (previousVersionId != null) {
            createRelation("Form", formId, "VERSION_OF", "Form", previousVersionId);
            createRelation("Form", previousVersionId, "SUPERSEDED_BY", "Form", formId);
        }

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
        ensureTopicExists(topicId);

        MongoCollection<Document> forms = mongoDatabase.getCollection("forms");
        List<Form> result = new ArrayList<>();

        for (Document doc : forms.find(new Document("topicId", topicId))) {
            result.add(mapDocToForm(doc));
        }

        return ResponseEntity.ok(result);
    }
}
