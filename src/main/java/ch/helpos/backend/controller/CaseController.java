package ch.helpos.backend.controller;

import ch.helpos.backend.models.Case;
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
@RequestMapping("/topics/{topicId}/forms/{formId}/cases")
public class CaseController extends AbstractController {

    public CaseController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        super(mongoDatabase, neo4jDriver);
    }
    
    // --- Helper: map MongoDB Document to Case ---
    @SuppressWarnings("unchecked")
    private Case mapDocToCase(Document doc) {
        return Case.builder()
                .id(doc.getObjectId("_id").toString())
                .title(doc.getString("title"))
                .description(doc.getString("description"))
                .status(doc.getString("status"))
                .parentForm(doc.getString("parentForm"))
                .tags((List<String>) doc.get("tags"))
                .build();
    }
    @PostMapping
    public ResponseEntity<Case> createCase(@PathVariable String topicId,
                                           @PathVariable String formId,
                                           @RequestBody Case legalCase) {
        // --- Save to MongoDB ---
        MongoCollection<Document> cases = mongoDatabase.getCollection("cases");
        Document doc = new Document("title", legalCase.getTitle())
                .append("description", legalCase.getDescription())
                .append("status", legalCase.getStatus())
                .append("parentForm", formId)
                .append("tags", legalCase.getTags());
        cases.insertOne(doc);

        String id = doc.getObjectId("_id").toString();

        // --- Create node in Neo4j ---
        createNode("Case", id);

        // --- Link to parent Form in Neo4j ---
        createRelation("Case", id, "BELONGS_TO", "Form", formId);

        return ResponseEntity.status(HttpStatus.CREATED).body(mapDocToCase(doc));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<Case> getCaseById(@PathVariable String topicId,
                                            @PathVariable String formId,
                                            @PathVariable String caseId) {
        MongoCollection<Document> cases = mongoDatabase.getCollection("cases");
        Document doc;
        try {
            doc = cases.find(new Document("_id", new ObjectId(caseId))
                    .append("parentForm", formId)).first();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid case id");
        }

        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        }

        return ResponseEntity.ok(mapDocToCase(doc));
    }

    @GetMapping
    public ResponseEntity<List<Case>> getAllCases(@PathVariable String topicId,
                                                  @PathVariable String formId) {
        MongoCollection<Document> cases = mongoDatabase.getCollection("cases");
        List<Case> result = new ArrayList<>();
        for (Document doc : cases.find(new Document("parentForm", formId))) {
            result.add(mapDocToCase(doc));
        }
        return ResponseEntity.ok(result);
    }
}
