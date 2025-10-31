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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/topics/{topicId}/forms/{formId}/cases")
public class CaseController extends AbstractController {

    public CaseController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
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
        Document formDoc = forms.find(new Document("_id", parseObjectId(formId, "Invalid form id"))).first();
        if (formDoc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Form not found");
        }
        if (!topicId.equals(formDoc.getString("topicId"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form does not belong to the provided topic");
        }
        return formDoc;
    }

    @SuppressWarnings("unchecked")
    private Case mapDocToCase(Document doc) {
        List<String> tags = doc.containsKey("tags") ? (List<String>) doc.get("tags") : Collections.emptyList();
        List<String> attachments = doc.containsKey("attachmentIds")
                ? (List<String>) doc.get("attachmentIds")
                : Collections.emptyList();
        Date createdAt = doc.getDate("createdAt");
        Date completedAt = doc.getDate("completedAt");

        return Case.builder()
                .id(doc.getObjectId("_id").toString())
                .title(doc.getString("title"))
                .description(doc.getString("description"))
                .status(doc.getString("status"))
                .topicId(doc.getString("topicId"))
                .formId(doc.getString("formId"))
                .formVersion(doc.getString("formVersion"))
                .profileId(doc.getString("profileId"))
                .extended(doc.getBoolean("extended", false))
                .outcome(doc.getString("outcome"))
                .closureNotes(doc.getString("closureNotes"))
                .tags(tags)
                .attachmentIds(attachments)
                .createdAt(createdAt != null ? createdAt.toInstant() : null)
                .completedAt(completedAt != null ? completedAt.toInstant() : null)
                .build();
    }

    // --- Routes ---

    @PostMapping
    public ResponseEntity<Case> createCase(@PathVariable String topicId,
                                           @PathVariable String formId,
                                           @RequestBody Case legalCase) {
        Document formDoc = requireForm(topicId, formId);

        MongoCollection<Document> cases = mongoDatabase.getCollection("cases");

        String status = legalCase.getStatus() != null ? legalCase.getStatus() : "OPEN";
        String formVersion = legalCase.getFormVersion() != null ? legalCase.getFormVersion() : formDoc.getString("version");
        Instant createdAt = legalCase.getCreatedAt() != null ? legalCase.getCreatedAt() : Instant.now();
        Instant completedAt = legalCase.getCompletedAt();

        List<String> tags = legalCase.getTags() != null ? legalCase.getTags() : new ArrayList<>();
        List<String> attachmentIds = legalCase.getAttachmentIds() != null ? legalCase.getAttachmentIds() : new ArrayList<>();
        boolean extendedFlag = legalCase.isExtended();

        Document doc = new Document("title", legalCase.getTitle())
                .append("description", legalCase.getDescription())
                .append("status", status)
                .append("topicId", topicId)
                .append("formId", formId)
                .append("formVersion", formVersion)
                .append("profileId", legalCase.getProfileId())
                .append("extended", extendedFlag)
                .append("outcome", legalCase.getOutcome())
                .append("closureNotes", legalCase.getClosureNotes())
                .append("tags", tags)
                .append("attachmentIds", attachmentIds)
                .append("createdAt", Date.from(createdAt));
        if (completedAt != null) {
            doc.append("completedAt", Date.from(completedAt));
        }

        cases.insertOne(doc);
        String id = doc.getObjectId("_id").toString();

        // --- Graph sync ---
        createNode("Case", id);
        createRelation("Case", id, "BELONGS_TO", "Form", formId);
        createRelation("Case", id, "BELONGS_TO", "Topic", topicId);
        if (legalCase.getProfileId() != null) {
            createRelation("Case", id, "FOR_PROFILE", "Profile", legalCase.getProfileId());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(mapDocToCase(doc));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<Case> getCaseById(@PathVariable String topicId,
                                            @PathVariable String formId,
                                            @PathVariable String caseId) {
        requireForm(topicId, formId);

        MongoCollection<Document> cases = mongoDatabase.getCollection("cases");
        Document doc = cases.find(new Document("_id", parseObjectId(caseId, "Invalid case id"))
                .append("formId", formId))
                .first();
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        }

        return ResponseEntity.ok(mapDocToCase(doc));
    }

    @GetMapping
    public ResponseEntity<List<Case>> getAllCases(@PathVariable String topicId,
                                                  @PathVariable String formId) {
        requireForm(topicId, formId);

        MongoCollection<Document> cases = mongoDatabase.getCollection("cases");
        List<Case> result = new ArrayList<>();
        for (Document doc : cases.find(new Document("formId", formId))) {
            result.add(mapDocToCase(doc));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{caseId}/close")
    public ResponseEntity<Case> closeCase(@PathVariable String topicId,
                                          @PathVariable String formId,
                                          @PathVariable String caseId,
                                          @RequestBody Case caseUpdate) {
        requireForm(topicId, formId);

        MongoCollection<Document> cases = mongoDatabase.getCollection("cases");
        ObjectId caseObjectId = parseObjectId(caseId, "Invalid case id");
        Document current = cases.find(new Document("_id", caseObjectId).append("formId", formId)).first();
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        }

        boolean extendedFlag = caseUpdate.isExtended();
        String status = caseUpdate.getStatus() != null ? caseUpdate.getStatus() : (extendedFlag ? "EXTENDED" : "CLOSED");
        Instant completionTime = caseUpdate.getCompletedAt() != null ? caseUpdate.getCompletedAt() : Instant.now();

        Document setDoc = new Document("status", status)
                .append("extended", extendedFlag)
                .append("outcome", caseUpdate.getOutcome())
                .append("closureNotes", caseUpdate.getClosureNotes())
                .append("completedAt", Date.from(completionTime));
        if (caseUpdate.getTags() != null) {
            setDoc.append("tags", caseUpdate.getTags());
        }
        if (caseUpdate.getAttachmentIds() != null) {
            setDoc.append("attachmentIds", caseUpdate.getAttachmentIds());
        }

        cases.updateOne(new Document("_id", caseObjectId),
                new Document("$set", setDoc));

        Document updated = cases.find(new Document("_id", caseObjectId)).first();
        return ResponseEntity.ok(mapDocToCase(updated));
    }
}
