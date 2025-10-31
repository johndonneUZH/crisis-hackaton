package ch.helpos.backend.controller;

import ch.helpos.backend.models.CaseRun;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/topics/{topicId}/forms/{formId}/cases/{caseId}/runs")
public class CaseRunController extends AbstractController {

    private static final int DEFAULT_SIMILAR_LIMIT = 5;

    public CaseRunController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        super(mongoDatabase, neo4jDriver);
    }

    // --- Public endpoints ---

    @PostMapping
    public ResponseEntity<CaseRun> createCaseRun(@PathVariable String topicId,
                                                 @PathVariable String formId,
                                                 @PathVariable String caseId,
                                                 @RequestBody CaseRun caseRun) {
        requireForm(topicId, formId);
        Document caseDoc = requireCase(topicId, formId, caseId);

        MongoCollection<Document> caseRuns = mongoDatabase.getCollection("caseRuns");

        List<CaseStep> steps = caseRun.getSteps() != null ? caseRun.getSteps() : Collections.emptyList();
        List<String> answeredQuestionIds = normalizeAnsweredQuestionIds(caseRun.getAnsweredQuestionIds(), steps);
        validateAnsweredQuestionIds(formId, answeredQuestionIds);
        validateStepsAgainstAnswered(steps, answeredQuestionIds);

        String status = caseRun.getStatus() != null ? caseRun.getStatus() : "ACTIVE";
        Instant createdAt = caseRun.getCreatedAt() != null ? caseRun.getCreatedAt() : Instant.now();
        Instant updatedAt = caseRun.getUpdatedAt() != null ? caseRun.getUpdatedAt() : createdAt;

        String profileId = caseRun.getProfileId() != null
                ? caseRun.getProfileId()
                : caseDoc.getString("profileId");

        List<String> tags = caseRun.getTags() != null ? caseRun.getTags() : new ArrayList<>();
        List<String> attachmentIds = caseRun.getAttachmentIds() != null ? caseRun.getAttachmentIds() : new ArrayList<>();
        boolean extendedFlag = Boolean.TRUE.equals(caseRun.getExtended());

        Document doc = new Document("caseId", caseId)
                .append("topicId", topicId)
                .append("formId", formId)
                .append("profileId", profileId)
                .append("lawyerId", caseRun.getLawyerId())
                .append("status", status)
                .append("extended", extendedFlag)
                .append("outcome", caseRun.getOutcome())
                .append("closureNotes", caseRun.getClosureNotes())
                .append("steps", mapStepsToDocuments(steps))
                .append("answeredQuestionIds", answeredQuestionIds)
                .append("tags", tags)
                .append("attachmentIds", attachmentIds)
                .append("createdAt", Date.from(createdAt))
                .append("updatedAt", Date.from(updatedAt));

        if (caseRun.getCompletedAt() != null) {
            doc.append("completedAt", Date.from(caseRun.getCompletedAt()));
        }

        caseRuns.insertOne(doc);
        String runId = doc.getObjectId("_id").toString();

        createNode("CaseRun", runId);
        createRelation("CaseRun", runId, "INSTANCE_OF", "Case", caseId);
        createRelation("CaseRun", runId, "BELONGS_TO", "Form", formId);
        createRelation("CaseRun", runId, "BELONGS_TO", "Topic", topicId);
        if (profileId != null) {
            createRelation("CaseRun", runId, "FOR_PROFILE", "Profile", profileId);
        }
        recordAnsweredRelations(runId, answeredQuestionIds);

        return ResponseEntity.status(HttpStatus.CREATED).body(mapDocToCaseRun(doc));
    }

    @GetMapping
    public ResponseEntity<List<CaseRun>> listCaseRuns(@PathVariable String topicId,
                                                      @PathVariable String formId,
                                                      @PathVariable String caseId) {
        requireForm(topicId, formId);
        requireCase(topicId, formId, caseId);

        MongoCollection<Document> caseRuns = mongoDatabase.getCollection("caseRuns");
        List<CaseRun> result = new ArrayList<>();
        for (Document doc : caseRuns.find(new Document("caseId", caseId))) {
            result.add(mapDocToCaseRun(doc));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{caseRunId}")
    public ResponseEntity<CaseRun> getCaseRun(@PathVariable String topicId,
                                              @PathVariable String formId,
                                              @PathVariable String caseId,
                                              @PathVariable String caseRunId) {
        requireForm(topicId, formId);
        requireCase(topicId, formId, caseId);

        MongoCollection<Document> caseRuns = mongoDatabase.getCollection("caseRuns");
        Document doc = caseRuns.find(new Document("_id", parseObjectId(caseRunId, "Invalid case run id"))
                        .append("caseId", caseId))
                .first();
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case run not found");
        }
        return ResponseEntity.ok(mapDocToCaseRun(doc));
    }

    @PutMapping("/{caseRunId}")
    public ResponseEntity<CaseRun> updateCaseRun(@PathVariable String topicId,
                                                 @PathVariable String formId,
                                                 @PathVariable String caseId,
                                                 @PathVariable String caseRunId,
                                                 @RequestBody CaseRun caseRunUpdate) {
        requireForm(topicId, formId);
        requireCase(topicId, formId, caseId);

        MongoCollection<Document> caseRuns = mongoDatabase.getCollection("caseRuns");
        ObjectId caseRunObjectId = parseObjectId(caseRunId, "Invalid case run id");
        Document current = caseRuns.find(new Document("_id", caseRunObjectId)
                .append("caseId", caseId)).first();

        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case run not found");
        }

        CaseRun existing = mapDocToCaseRun(current);
        boolean stepsProvided = caseRunUpdate.getSteps() != null;
        boolean answeredProvided = caseRunUpdate.getAnsweredQuestionIds() != null;

        List<CaseStep> effectiveSteps = stepsProvided ? caseRunUpdate.getSteps() : existing.getSteps();
        List<String> answeredQuestionIds = answeredProvided
                ? normalizeAnsweredQuestionIds(caseRunUpdate.getAnsweredQuestionIds(), effectiveSteps)
                : existing.getAnsweredQuestionIds();

        if (stepsProvided && !answeredProvided) {
            answeredQuestionIds = normalizeAnsweredQuestionIds(Collections.emptyList(), effectiveSteps);
        }

        validateAnsweredQuestionIds(formId, answeredQuestionIds);
        validateStepsAgainstAnswered(effectiveSteps, answeredQuestionIds);

        if ("CLOSED".equalsIgnoreCase(caseRunUpdate.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use the close endpoint to close a case run");
        }

        Instant updatedAt = Instant.now();
        Document setDoc = new Document("updatedAt", Date.from(updatedAt));

        if (stepsProvided || answeredProvided) {
            setDoc.append("steps", mapStepsToDocuments(effectiveSteps));
            setDoc.append("answeredQuestionIds", answeredQuestionIds);
        }

        if (caseRunUpdate.getStatus() != null) {
            setDoc.append("status", caseRunUpdate.getStatus());
        }
        if (caseRunUpdate.getOutcome() != null) {
            setDoc.append("outcome", caseRunUpdate.getOutcome());
        }
        if (caseRunUpdate.getClosureNotes() != null) {
            setDoc.append("closureNotes", caseRunUpdate.getClosureNotes());
        }
        if (caseRunUpdate.getTags() != null) {
            setDoc.append("tags", caseRunUpdate.getTags());
        }
        if (caseRunUpdate.getAttachmentIds() != null) {
            setDoc.append("attachmentIds", caseRunUpdate.getAttachmentIds());
        }
        if (caseRunUpdate.getLawyerId() != null) {
            setDoc.append("lawyerId", caseRunUpdate.getLawyerId());
        }
        if (caseRunUpdate.getExtended() != null) {
            setDoc.append("extended", Boolean.TRUE.equals(caseRunUpdate.getExtended()));
        }

        caseRuns.updateOne(new Document("_id", caseRunObjectId), new Document("$set", setDoc));

        Document updated = caseRuns.find(new Document("_id", caseRunObjectId)).first();
        CaseRun response = mapDocToCaseRun(updated);
        recordAnsweredRelations(caseRunId, response.getAnsweredQuestionIds());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{caseRunId}/close")
    public ResponseEntity<CaseRun> closeCaseRun(@PathVariable String topicId,
                                                @PathVariable String formId,
                                                @PathVariable String caseId,
                                                @PathVariable String caseRunId,
                                                @RequestBody CaseRun caseRunUpdate) {
        requireForm(topicId, formId);
        requireCase(topicId, formId, caseId);

        MongoCollection<Document> caseRuns = mongoDatabase.getCollection("caseRuns");
        ObjectId caseRunObjectId = parseObjectId(caseRunId, "Invalid case run id");
        Document current = caseRuns.find(new Document("_id", caseRunObjectId)
                .append("caseId", caseId)).first();

        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case run not found");
        }

        CaseRun existing = mapDocToCaseRun(current);
        if (existing.getCompletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Case run is already closed");
        }

        Instant completionTime = caseRunUpdate.getCompletedAt() != null ? caseRunUpdate.getCompletedAt() : Instant.now();
        boolean extendedFlag = caseRunUpdate.getExtended() != null
                ? Boolean.TRUE.equals(caseRunUpdate.getExtended())
                : Boolean.TRUE.equals(existing.getExtended());
        String status = caseRunUpdate.getStatus() != null ? caseRunUpdate.getStatus() : (extendedFlag ? "EXTENDED" : "CLOSED");

        Document setDoc = new Document("status", status)
                .append("extended", extendedFlag)
                .append("outcome", caseRunUpdate.getOutcome())
                .append("closureNotes", caseRunUpdate.getClosureNotes())
                .append("completedAt", Date.from(completionTime))
                .append("updatedAt", Date.from(completionTime));
        if (caseRunUpdate.getTags() != null) {
            setDoc.append("tags", caseRunUpdate.getTags());
        }
        if (caseRunUpdate.getAttachmentIds() != null) {
            setDoc.append("attachmentIds", caseRunUpdate.getAttachmentIds());
        }

        caseRuns.updateOne(new Document("_id", caseRunObjectId),
                new Document("$set", setDoc));

        Document updated = caseRuns.find(new Document("_id", caseRunObjectId)).first();
        CaseRun response = mapDocToCaseRun(updated);
        recordAggregation(topicId, formId, caseId, response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{caseRunId}/similar")
    public ResponseEntity<List<CaseRun>> findSimilarRuns(@PathVariable String topicId,
                                                         @PathVariable String formId,
                                                         @PathVariable String caseId,
                                                         @PathVariable String caseRunId) {
        requireForm(topicId, formId);
        requireCase(topicId, formId, caseId);
        List<SimilarityResult> results = findSimilarRunsFromNeo4j(formId, caseRunId, DEFAULT_SIMILAR_LIMIT);
        return ResponseEntity.ok(resolveSimilarCaseRuns(results));
    }

    @PostMapping("/similar")
    public ResponseEntity<List<CaseRun>> findSimilarRunsForAnswers(@PathVariable String topicId,
                                                                   @PathVariable String formId,
                                                                   @PathVariable String caseId,
                                                                   @RequestBody SimilarRunQuery query) {
        requireForm(topicId, formId);
        requireCase(topicId, formId, caseId);

        List<String> answeredIds = query.answeredQuestionIds() != null ? query.answeredQuestionIds() : Collections.emptyList();
        if (answeredIds.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        validateAnsweredQuestionIds(formId, answeredIds);
        List<SimilarityResult> results = findSimilarRunsFromNeo4j(formId, answeredIds, query.excludeCaseRunId(), DEFAULT_SIMILAR_LIMIT);
        return ResponseEntity.ok(resolveSimilarCaseRuns(results));
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

    private Document requireCase(String topicId, String formId, String caseId) {
        MongoCollection<Document> cases = mongoDatabase.getCollection("cases");
        Document caseDoc = cases.find(new Document("_id", parseObjectId(caseId, "Invalid case id"))
                .append("formId", formId)
                .append("topicId", topicId)).first();
        if (caseDoc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        }
        return caseDoc;
    }

    private List<Document> mapStepsToDocuments(List<CaseStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return new ArrayList<>();
        }
        List<Document> docs = new ArrayList<>();
        for (CaseStep step : steps) {
            if (step.getQuestionId() == null || step.getQuestionId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Step is missing a question id");
            }
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
    private CaseRun mapDocToCaseRun(Document doc) {
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
        Date updatedAt = doc.getDate("updatedAt");
        Date completedAt = doc.getDate("completedAt");

        return CaseRun.builder()
                .id(doc.getObjectId("_id").toString())
                .caseId(doc.getString("caseId"))
                .topicId(doc.getString("topicId"))
                .formId(doc.getString("formId"))
                .profileId(doc.getString("profileId"))
                .lawyerId(doc.getString("lawyerId"))
                .status(doc.getString("status"))
                .extended(doc.getBoolean("extended", false))
                .outcome(doc.getString("outcome"))
                .closureNotes(doc.getString("closureNotes"))
                .steps(mapDocumentsToSteps(stepDocs))
                .answeredQuestionIds(answeredIds)
                .tags(tags)
                .attachmentIds(attachments)
                .createdAt(createdAt != null ? createdAt.toInstant() : null)
                .updatedAt(updatedAt != null ? updatedAt.toInstant() : null)
                .completedAt(completedAt != null ? completedAt.toInstant() : null)
                .build();
    }

    private List<String> normalizeAnsweredQuestionIds(List<String> answeredQuestionIds, List<CaseStep> steps) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (answeredQuestionIds != null) {
            for (String id : answeredQuestionIds) {
                if (id != null && !id.isBlank()) {
                    ordered.add(id);
                }
            }
        }
        if (ordered.isEmpty() && steps != null) {
            for (CaseStep step : steps) {
                if (step.getQuestionId() != null && !step.getQuestionId().isBlank()) {
                    ordered.add(step.getQuestionId());
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    private void validateAnsweredQuestionIds(String formId, List<String> answeredQuestionIds) {
        if (answeredQuestionIds == null || answeredQuestionIds.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        List<ObjectId> objectIds = new ArrayList<>();
        for (String id : answeredQuestionIds) {
            if (id == null || id.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answered question ids cannot be blank");
            }
            if (!seen.add(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate question id in answered list: " + id);
            }
            objectIds.add(parseObjectId(id, "Invalid question id: " + id));
        }

        MongoCollection<Document> questions = mongoDatabase.getCollection("questions");
        List<Document> docs = questions.find(new Document("_id", new Document("$in", objectIds))).into(new ArrayList<>());
        Map<String, Document> docById = new HashMap<>();
        for (Document doc : docs) {
            docById.put(doc.getObjectId("_id").toString(), doc);
        }

        Set<String> answeredSet = new HashSet<>();
        for (String id : answeredQuestionIds) {
            Document doc = docById.get(id);
            if (doc == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question not found for id: " + id);
            }
            if (!formId.equals(doc.getString("formId"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question " + id + " does not belong to the form");
            }
            String parentId = doc.getString("parentQuestionId");
            if (parentId != null && !answeredSet.contains(parentId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Question " + id + " depends on " + parentId + " being answered first");
            }
            answeredSet.add(id);
        }
    }

    private void validateStepsAgainstAnswered(List<CaseStep> steps, List<String> answeredQuestionIds) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < answeredQuestionIds.size(); i++) {
            order.put(answeredQuestionIds.get(i), i);
        }
        int lastIndex = -1;
        for (CaseStep step : steps) {
            String questionId = step.getQuestionId();
            if (questionId == null || questionId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Step is missing a question id");
            }
            Integer index = order.get(questionId);
            if (index == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Step question " + questionId + " is not present in answeredQuestionIds");
            }
            if (index < lastIndex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Steps are out of order compared to answered questions");
            }
            lastIndex = index;
        }
    }

    private void recordAnsweredRelations(String caseRunId, List<String> answeredQuestionIds) {
        if (answeredQuestionIds == null || answeredQuestionIds.isEmpty()) {
            return;
        }
        for (String questionId : answeredQuestionIds) {
            createRelation("CaseRun", caseRunId, "ANSWERED", "Question", questionId);
        }
    }

    private void recordAggregation(String topicId, String formId, String caseId, CaseRun caseRun) {
        List<String> signature = caseRun.getAnsweredQuestionIds();
        if (signature == null || signature.isEmpty()) {
            return;
        }
        MongoCollection<Document> aggregates = mongoDatabase.getCollection("caseRunAggregations");
        Instant now = Instant.now();

        Document query = new Document("topicId", topicId)
                .append("formId", formId)
                .append("caseId", caseId)
                .append("answerSignature", signature)
                .append("outcome", caseRun.getOutcome());

        Document existing = aggregates.find(query).first();
        if (existing != null) {
            aggregates.updateOne(
                    new Document("_id", existing.getObjectId("_id")),
                    new Document("$inc", new Document("usefulCount", 1))
                            .append("$set", new Document("lastRunId", caseRun.getId())
                                    .append("lastMatchedAt", Date.from(now)))
            );
        } else {
            Document doc = new Document("topicId", topicId)
                    .append("formId", formId)
                    .append("caseId", caseId)
                    .append("answerSignature", signature)
                    .append("outcome", caseRun.getOutcome())
                    .append("usefulCount", 1)
                    .append("lastRunId", caseRun.getId())
                    .append("createdAt", Date.from(now))
                    .append("lastMatchedAt", Date.from(now));
            aggregates.insertOne(doc);
        }
    }

    private List<CaseRun> resolveSimilarCaseRuns(List<SimilarityResult> results) {
        if (results.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (SimilarityResult result : results) {
            ids.add(result.caseRunId());
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

        MongoCollection<Document> runsCollection = mongoDatabase.getCollection("caseRuns");
        List<Document> docs = runsCollection.find(new Document("_id", new Document("$in", objectIds)))
                .into(new ArrayList<>());
        Map<String, Document> docMap = new HashMap<>();
        for (Document doc : docs) {
            docMap.put(doc.getObjectId("_id").toString(), doc);
        }

        List<CaseRun> ordered = new ArrayList<>();
        for (SimilarityResult result : results) {
            Document doc = docMap.get(result.caseRunId());
            if (doc != null) {
                ordered.add(mapDocToCaseRun(doc));
            }
        }
        return ordered;
    }

    private List<SimilarityResult> findSimilarRunsFromNeo4j(String formId, String caseRunId, int limit) {
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                var cursor = tx.run("""
                            MATCH (target:CaseRun {id: $targetId})-[:ANSWERED]->(tq:Question)
                            WITH collect(DISTINCT tq.id) AS targetIds
                            WHERE size(targetIds) > 0
                            MATCH (candidate:CaseRun)-[:ANSWERED]->(cq:Question)
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
                            RETURN candidate.id AS caseRunId,
                                   (1.0 * intersection) / unionSize AS score
                            ORDER BY score DESC
                            LIMIT $limit
                            """,
                        Values.parameters(
                                "targetId", caseRunId,
                                "formId", formId,
                                "limit", limit
                        ));
                return cursor.list(r -> new SimilarityResult(
                        r.get("caseRunId").asString(),
                        r.get("score").asDouble()));
            });
        }
    }

    private List<SimilarityResult> findSimilarRunsFromNeo4j(String formId,
                                                            List<String> answeredQuestionIds,
                                                            String excludeCaseRunId,
                                                            int limit) {
        if (answeredQuestionIds == null || answeredQuestionIds.isEmpty()) {
            return Collections.emptyList();
        }
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                var cursor = tx.run("""
                            WITH $targetIds AS targetIds
                            MATCH (candidate:CaseRun)-[:ANSWERED]->(cq:Question)
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
                            RETURN candidate.id AS caseRunId,
                                   (1.0 * intersection) / unionSize AS score
                            ORDER BY score DESC
                            LIMIT $limit
                            """,
                        Values.parameters(
                                "targetIds", answeredQuestionIds,
                                "formId", formId,
                                "excludeId", excludeCaseRunId,
                                "limit", limit
                        ));
                return cursor.list(r -> new SimilarityResult(
                        r.get("caseRunId").asString(),
                        r.get("score").asDouble()));
            });
        }
    }

    private record SimilarityResult(String caseRunId, double score) {}

    private record SimilarRunQuery(List<String> answeredQuestionIds, String excludeCaseRunId) {}
}
