package ch.helpos.backend.controller;

import ch.helpos.backend.models.Case;
import ch.helpos.backend.models.CaseStep;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/topics/{topicId}/forms/{formId}/cases")
public class CaseController extends AbstractController {

    private static final int DEFAULT_SIMILAR_LIMIT = 5;

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

    private List<Document> mapStepsToDocuments(List<CaseStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return new ArrayList<>();
        }
        List<Document> docs = new ArrayList<>();
        for (CaseStep step : steps) {
            Document stepDoc = new Document("questionId", step.getQuestionId())
                    .append("answer", step.getAnswer())
                    .append("notes", step.getNotes())
                    .append("attachmentIds", step.getAttachmentIds() != null ? step.getAttachmentIds() : new ArrayList<String>());
            if (step.getAnsweredAt() != null) {
                stepDoc.append("answeredAt", Date.from(step.getAnsweredAt()));
            }
            docs.add(stepDoc);
        }
        return docs;
    }

    @SuppressWarnings("unchecked")
    private List<CaseStep> mapDocumentsToSteps(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        List<CaseStep> steps = new ArrayList<>();
        for (Document doc : docs) {
            Date answeredAtDate = doc.getDate("answeredAt");
            List<String> attachmentIds = doc.containsKey("attachmentIds")
                    ? (List<String>) doc.get("attachmentIds")
                    : Collections.emptyList();
            steps.add(CaseStep.builder()
                    .questionId(doc.getString("questionId"))
                    .answer(doc.getString("answer"))
                    .notes(doc.getString("notes"))
                    .attachmentIds(attachmentIds)
                    .answeredAt(answeredAtDate != null ? answeredAtDate.toInstant() : null)
                    .build());
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private Case mapDocToCase(Document doc) {
        List<String> tags = doc.containsKey("tags") ? (List<String>) doc.get("tags") : Collections.emptyList();
        List<String> attachments = doc.containsKey("attachmentIds")
                ? (List<String>) doc.get("attachmentIds")
                : Collections.emptyList();
        List<String> answeredIds = doc.containsKey("answeredQuestionIds")
                ? (List<String>) doc.get("answeredQuestionIds")
                : Collections.emptyList();
        List<Document> stepDocs = doc.containsKey("steps")
                ? (List<Document>) doc.get("steps")
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
                .steps(mapDocumentsToSteps(stepDocs))
                .answeredQuestionIds(answeredIds)
                .tags(tags)
                .attachmentIds(attachments)
                .createdAt(createdAt != null ? createdAt.toInstant() : null)
                .completedAt(completedAt != null ? completedAt.toInstant() : null)
                .build();
    }

    @PostMapping
    public ResponseEntity<Case> createCase(@PathVariable String topicId,
                                           @PathVariable String formId,
                                           @RequestBody Case legalCase) {
        Document formDoc = requireForm(topicId, formId);

        MongoCollection<Document> cases = mongoDatabase.getCollection("cases");

        List<CaseStep> steps = legalCase.getSteps() != null ? legalCase.getSteps() : Collections.emptyList();
        List<String> answeredQuestionIds = legalCase.getAnsweredQuestionIds();
        if (answeredQuestionIds == null || answeredQuestionIds.isEmpty()) {
            LinkedHashSet<String> orderedIds = new LinkedHashSet<>();
            for (CaseStep step : steps) {
                if (step.getQuestionId() != null) {
                    orderedIds.add(step.getQuestionId());
                }
            }
            answeredQuestionIds = new ArrayList<>(orderedIds);
        }
        List<String> tags = legalCase.getTags() != null ? legalCase.getTags() : new ArrayList<>();
        List<String> attachmentIds = legalCase.getAttachmentIds() != null ? legalCase.getAttachmentIds() : new ArrayList<>();

        String status = legalCase.getStatus() != null ? legalCase.getStatus() : "OPEN";
        String formVersion = legalCase.getFormVersion() != null ? legalCase.getFormVersion() : formDoc.getString("version");
        Instant createdAt = legalCase.getCreatedAt() != null ? legalCase.getCreatedAt() : Instant.now();
        Instant completedAt = legalCase.getCompletedAt();

        Document doc = new Document("title", legalCase.getTitle())
                .append("description", legalCase.getDescription())
                .append("status", status)
                .append("topicId", topicId)
                .append("formId", formId)
                .append("formVersion", formVersion)
                .append("profileId", legalCase.getProfileId())
                .append("extended", legalCase.isExtended())
                .append("outcome", legalCase.getOutcome())
                .append("closureNotes", legalCase.getClosureNotes())
                .append("steps", mapStepsToDocuments(steps))
                .append("answeredQuestionIds", answeredQuestionIds)
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
        for (String questionId : answeredQuestionIds) {
            if (questionId != null) {
                createRelation("Case", id, "ANSWERED", "Question", questionId);
            }
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

    @GetMapping("/{caseId}/similar")
    public ResponseEntity<List<Case>> findSimilarCases(@PathVariable String topicId,
                                                       @PathVariable String formId,
                                                       @PathVariable String caseId) {
        requireForm(topicId, formId);
        List<SimilarityResult> results = findSimilarCasesFromNeo4j(formId, caseId, DEFAULT_SIMILAR_LIMIT);
        return ResponseEntity.ok(resolveSimilarCases(results));
    }

    @PostMapping("/similar")
    public ResponseEntity<List<Case>> findSimilarCasesForAnswers(@PathVariable String topicId,
                                                                 @PathVariable String formId,
                                                                 @RequestBody SimilarCaseQuery query) {
        requireForm(topicId, formId);
        List<String> answeredIds = query.answeredQuestionIds() != null ? query.answeredQuestionIds() : Collections.emptyList();
        if (answeredIds.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<SimilarityResult> results = findSimilarCasesFromNeo4j(formId, answeredIds, query.excludeCaseId(), DEFAULT_SIMILAR_LIMIT);
        return ResponseEntity.ok(resolveSimilarCases(results));
    }

    private List<Case> resolveSimilarCases(List<SimilarityResult> results) {
        if (results.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (SimilarityResult result : results) {
            ids.add(result.caseId());
        }

        List<ObjectId> objectIds = new ArrayList<>();
        for (String id : ids) {
            try {
                objectIds.add(new ObjectId(id));
            } catch (IllegalArgumentException ignored) {
                // skip invalid ids
            }
        }
        if (objectIds.isEmpty()) {
            return Collections.emptyList();
        }

        MongoCollection<Document> casesCollection = mongoDatabase.getCollection("cases");
        List<Document> docs = casesCollection.find(new Document("_id", new Document("$in", objectIds)))
                .into(new ArrayList<>());
        Map<String, Document> docMap = new HashMap<>();
        for (Document doc : docs) {
            docMap.put(doc.getObjectId("_id").toString(), doc);
        }

        List<Case> ordered = new ArrayList<>();
        for (SimilarityResult result : results) {
            Document doc = docMap.get(result.caseId());
            if (doc != null) {
                ordered.add(mapDocToCase(doc));
            }
        }
        return ordered;
    }

    private List<SimilarityResult> findSimilarCasesFromNeo4j(String formId, String caseId, int limit) {
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                var cursor = tx.run("""
                            MATCH (target:Case {id: $targetId})-[:ANSWERED]->(tq:Question)
                            WITH collect(DISTINCT tq.id) AS targetIds
                            WHERE size(targetIds) > 0
                            MATCH (candidate:Case)-[:ANSWERED]->(cq:Question)
                            WHERE candidate.id <> $targetId
                              AND (candidate)-[:BELONGS_TO]->(:Form {id: $formId})
                            WITH candidate, targetIds, collect(DISTINCT cq.id) AS candidateIds
                            WITH candidate,
                                 targetIds,
                                 candidateIds,
                                 size([id IN candidateIds WHERE id IN targetIds]) AS intersection,
                                 size(targetIds) AS targetSize,
                                 size(candidateIds) AS candidateSize
                            WITH candidate,
                                 intersection,
                                 targetSize,
                                 candidateSize,
                                 (targetSize + candidateSize - intersection) AS unionSize
                            WHERE intersection > 0 AND unionSize > 0
                            RETURN candidate.id AS caseId,
                                   (1.0 * intersection) / unionSize AS score
                            ORDER BY score DESC
                            LIMIT $limit
                            """,
                        Values.parameters(
                                "targetId", caseId,
                                "formId", formId,
                                "limit", limit
                        ));
                return cursor.list(r -> new SimilarityResult(
                        r.get("caseId").asString(),
                        r.get("score").asDouble()));
            });
        }
    }

    private List<SimilarityResult> findSimilarCasesFromNeo4j(String formId,
                                                             List<String> answeredQuestionIds,
                                                             String excludeCaseId,
                                                             int limit) {
        if (answeredQuestionIds == null || answeredQuestionIds.isEmpty()) {
            return Collections.emptyList();
        }
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                var cursor = tx.run("""
                            WITH $targetIds AS targetIds
                            MATCH (candidate:Case)-[:ANSWERED]->(cq:Question)
                            WHERE (candidate)-[:BELONGS_TO]->(:Form {id: $formId})
                              AND ($excludeId IS NULL OR candidate.id <> $excludeId)
                            WITH candidate, targetIds, collect(DISTINCT cq.id) AS candidateIds
                            WITH candidate,
                                 targetIds,
                                 candidateIds,
                                 size([id IN candidateIds WHERE id IN targetIds]) AS intersection,
                                 size(targetIds) AS targetSize,
                                 size(candidateIds) AS candidateSize
                            WITH candidate,
                                 intersection,
                                 targetSize,
                                 candidateSize,
                                 (targetSize + candidateSize - intersection) AS unionSize
                            WHERE intersection > 0 AND unionSize > 0
                            RETURN candidate.id AS caseId,
                                   (1.0 * intersection) / unionSize AS score
                            ORDER BY score DESC
                            LIMIT $limit
                            """,
                        Values.parameters(
                                "targetIds", answeredQuestionIds,
                                "formId", formId,
                                "excludeId", excludeCaseId,
                                "limit", limit
                        ));
                return cursor.list(r -> new SimilarityResult(
                        r.get("caseId").asString(),
                        r.get("score").asDouble()));
            });
        }
    }

    private record SimilarityResult(String caseId, double score) {}

    private record SimilarCaseQuery(List<String> answeredQuestionIds, String excludeCaseId) {}

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
