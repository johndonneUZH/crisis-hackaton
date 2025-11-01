package ch.helpos.backend.controller;

import ch.helpos.backend.models.Case;
import ch.helpos.backend.models.CaseRun;
import ch.helpos.backend.models.CaseStep;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/topics/{topicId}/forms/{formId}")
public class CaseController extends AbstractController {

    private final MongoCollection<Document> runCollection;
    private final MongoCollection<Document> caseCollection;

    public CaseController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        super(mongoDatabase, neo4jDriver);
        this.runCollection = mongoDatabase.getCollection("runs");
        this.caseCollection = mongoDatabase.getCollection("cases");
    }

    @PostMapping("/runs")
    public ResponseEntity<CaseRun> createRun(@PathVariable String topicId,
                                             @PathVariable String formId,
                                             @RequestBody CaseRun payload) {
        Instant now = Instant.now();
        Document doc = new Document("topicId", topicId)
                .append("formId", formId)
                .append("profileId", payload.getProfileId())
                .append("status", payload.getStatus() != null ? payload.getStatus() : "RUNNING")
                .append("startedAt", Date.from(payload.getStartedAt() != null ? payload.getStartedAt() : now));

        if (payload.getSteps() != null) {
            doc.append("steps", mapStepsToDocuments(payload.getSteps()));
        }
        if (payload.getOutcome() != null) {
            doc.append("outcome", payload.getOutcome());
        }
        if (payload.getClosedAt() != null) {
            doc.append("closedAt", Date.from(payload.getClosedAt()));
        }

        runCollection.insertOne(doc);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapRun(doc));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<CaseRun> getRun(@PathVariable String topicId,
                                          @PathVariable String formId,
                                          @PathVariable String runId) {
        Document doc = findRun(topicId, formId, runId);
        return ResponseEntity.ok(mapRun(doc));
    }

    @PutMapping("/runs/{runId}")
    public ResponseEntity<CaseRun> updateRun(@PathVariable String topicId,
                                             @PathVariable String formId,
                                             @PathVariable String runId,
                                             @RequestBody CaseRun payload) {
        Document existing = findRun(topicId, formId, runId);
        Document updates = new Document();

        if (payload.getProfileId() != null) {
            updates.append("profileId", payload.getProfileId());
        }
        if (payload.getStatus() != null) {
            updates.append("status", payload.getStatus());
        }
        if (payload.getOutcome() != null) {
            updates.append("outcome", payload.getOutcome());
        }
        if (payload.getStartedAt() != null) {
            updates.append("startedAt", Date.from(payload.getStartedAt()));
        }
        if (payload.getClosedAt() != null) {
            updates.append("closedAt", Date.from(payload.getClosedAt()));
        }
        if (payload.getSteps() != null) {
            updates.append("steps", mapStepsToDocuments(payload.getSteps()));
        }

        if (!updates.isEmpty()) {
            updates.append("updatedAt", Date.from(Instant.now()));
            runCollection.updateOne(new Document("_id", existing.getObjectId("_id")), new Document("$set", updates));
        }

        Document updated = findRun(topicId, formId, runId);
        return ResponseEntity.ok(mapRun(updated));
    }

    @GetMapping("/cases")
    public ResponseEntity<List<Case>> listCases(@PathVariable String topicId,
                                                @PathVariable String formId) {
        List<Document> docs = caseCollection.find(new Document("topicId", topicId).append("formId", formId))
                .into(new ArrayList<>());
        docs.sort(Comparator.comparing((Document d) -> d.getInteger("frequency", 0)).reversed());
        return ResponseEntity.ok(docs.stream().map(this::mapCase).collect(Collectors.toList()));
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<Case> getCase(@PathVariable String topicId,
                                        @PathVariable String formId,
                                        @PathVariable String caseId) {
        Document doc = findCase(topicId, formId, caseId);
        return ResponseEntity.ok(mapCase(doc));
    }

    @GetMapping("/runs/{runId}/similar")
    public ResponseEntity<List<Case>> findSimilarCases(@PathVariable String topicId,
                                                       @PathVariable String formId,
                                                       @PathVariable String runId) {
        Document runDoc = findRun(topicId, formId, runId);
        CaseRun run = mapRun(runDoc);
        List<CaseStep> runSteps = run.getSteps() != null ? run.getSteps() : Collections.emptyList();
        if (runSteps.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<Document> candidateDocs = caseCollection.find(new Document("topicId", topicId).append("formId", formId))
                .into(new ArrayList<>());
        List<CaseScore> scored = new ArrayList<>();

        for (Document doc : candidateDocs) {
            Case candidate = mapCase(doc);
            double score = scoreCase(runSteps, candidate.getSteps() != null ? candidate.getSteps() : Collections.emptyList());
            if (score > 0d) {
                scored.add(new CaseScore(candidate, score));
            }
        }

        scored.sort(Comparator.<CaseScore>comparingDouble(CaseScore::score).reversed()
                .thenComparing(cs -> cs.caseData().getFrequency(), Comparator.nullsLast(Comparator.reverseOrder())));

        List<Case> result = scored.stream()
                .limit(5)
                .map(CaseScore::caseData)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/runs/{runId}/close")
    public ResponseEntity<Case> closeRun(@PathVariable String topicId,
                                         @PathVariable String formId,
                                         @PathVariable String runId,
                                         @RequestBody(required = false) CaseRun closure) {
        Document runDoc = findRun(topicId, formId, runId);
        CaseRun run = mapRun(runDoc);

        List<CaseStep> finalSteps = closure != null && closure.getSteps() != null
                ? closure.getSteps()
                : (run.getSteps() != null ? run.getSteps() : Collections.emptyList());

        if (finalSteps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot close a run without recorded steps");
        }

        String outcome = closure != null && closure.getOutcome() != null
                ? closure.getOutcome()
                : run.getOutcome();

        if (outcome == null || outcome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Outcome is required to close a run");
        }

        Instant closedAt = closure != null && closure.getClosedAt() != null ? closure.getClosedAt() : Instant.now();
        String closureNotes = closure != null && closure.getClosureNotes() != null ? closure.getClosureNotes() : run.getClosureNotes();
        runCollection.updateOne(
                new Document("_id", runDoc.getObjectId("_id")),
                new Document("$set", new Document("steps", mapStepsToDocuments(finalSteps))
                        .append("outcome", outcome)
                        .append("status", "COMPLETED")
                        .append("closedAt", Date.from(closedAt))
                        .append("closureNotes", closureNotes))
        );

        Case aggregated = upsertCase(topicId, formId, run.getProfileId(), finalSteps, outcome, closureNotes);
        return ResponseEntity.ok(aggregated);
    }

    private Case upsertCase(String topicId,
                            String formId,
                            String profileId,
                            List<CaseStep> steps,
                            String outcome,
                            String closureNotes) {
        List<Document> candidates = caseCollection.find(new Document("topicId", topicId)
                        .append("formId", formId)
                        .append("outcome", outcome))
                .into(new ArrayList<>());

        for (Document candidate : candidates) {
            List<CaseStep> candidateSteps = mapStepsFromDocuments(candidate.getList("steps", Document.class, Collections.emptyList()));
            if (stepsEqual(steps, candidateSteps)) {
                Document setDoc = new Document("completedAt", Date.from(Instant.now()));
                if (closureNotes != null && !closureNotes.isBlank()) {
                    setDoc.append("closureNotes", closureNotes);
                }
                caseCollection.updateOne(
                        new Document("_id", candidate.getObjectId("_id")),
                        new Document("$inc", new Document("frequency", 1))
                                .append("$set", setDoc));
                Document updated = caseCollection.find(new Document("_id", candidate.getObjectId("_id"))).first();
                return mapCase(Objects.requireNonNull(updated));
            }
        }

        Instant now = Instant.now();
        List<Document> stepDocs = mapStepsToDocuments(steps);
        List<String> answeredIds = steps.stream()
                .map(CaseStep::getQuestionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Document newDoc = new Document("topicId", topicId)
                .append("formId", formId)
                .append("profileId", profileId)
                .append("status", "CLOSED")
                .append("outcome", outcome)
                .append("frequency", 1)
                .append("steps", stepDocs)
                .append("answeredQuestionIds", answeredIds)
                .append("title", outcome != null && !outcome.isBlank() ? outcome : "Case")
                .append("description", "")
                .append("closureNotes", closureNotes)
                .append("createdAt", Date.from(now))
                .append("completedAt", Date.from(now));

        caseCollection.insertOne(newDoc);
        return mapCase(newDoc);
    }

    private Document findRun(String topicId, String formId, String runId) {
        ObjectId objectId = parseId(runId);
        Document doc = runCollection.find(new Document("_id", objectId)).first();
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        }
        String storedTopic = doc.getString("topicId");
        String storedForm = doc.getString("formId");

        boolean topicMismatch = storedTopic != null && !Objects.equals(storedTopic, topicId);
        boolean formMismatch = storedForm != null && !Objects.equals(storedForm, formId);
        if (topicMismatch || formMismatch) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found for provided form");
        }

        Document patch = new Document();
        if (storedTopic == null) {
            patch.append("topicId", topicId);
        }
        if (storedForm == null) {
            patch.append("formId", formId);
        }
        if (!patch.isEmpty()) {
            runCollection.updateOne(new Document("_id", objectId), new Document("$set", patch));
            doc.putAll(patch);
        }
        return doc;
    }

    private Document findCase(String topicId, String formId, String caseId) {
        Document filter = new Document("_id", parseId(caseId))
                .append("topicId", topicId)
                .append("formId", formId);
        Document doc = caseCollection.find(filter).first();
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        }
        return doc;
    }

    private ObjectId parseId(String rawId) {
        try {
            return new ObjectId(rawId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid identifier");
        }
    }

    private CaseRun mapRun(Document doc) {
        List<CaseStep> steps = mapStepsFromDocuments(doc.getList("steps", Document.class, Collections.emptyList()));
        return CaseRun.builder()
                .id(doc.getObjectId("_id").toHexString())
                .profileId(doc.getString("profileId"))
                .formId(doc.getString("formId"))
                .topicId(doc.getString("topicId"))
                .steps(steps)
                .outcome(doc.getString("outcome"))
                .status(doc.getString("status"))
                .startedAt(toInstant(doc.getDate("startedAt")))
                .closedAt(toInstant(doc.getDate("closedAt")))
                .closureNotes(doc.getString("closureNotes"))
                .build();
    }

    private Case mapCase(Document doc) {
        List<CaseStep> steps = mapStepsFromDocuments(doc.getList("steps", Document.class, Collections.emptyList()));
        return Case.builder()
                .id(doc.getObjectId("_id").toHexString())
                .title(doc.getString("title"))
                .description(doc.getString("description"))
                .status(doc.getString("status"))
                .topicId(doc.getString("topicId"))
                .formId(doc.getString("formId"))
                .formVersion(doc.getString("formVersion"))
                .profileId(doc.getString("profileId"))
                .outcome(doc.getString("outcome"))
                .closureNotes(doc.getString("closureNotes"))
                .createdAt(toInstant(doc.getDate("createdAt")))
                .completedAt(toInstant(doc.getDate("completedAt")))
                .steps(steps)
                .answeredQuestionIds(doc.getList("answeredQuestionIds", String.class))
                .frequency(doc.getInteger("frequency", 0))
                .build();
    }

    private List<Document> mapStepsToDocuments(List<CaseStep> steps) {
        if (steps == null) {
            return Collections.emptyList();
        }
        return steps.stream().map(step -> {
            Document doc = new Document();
            if (step.getQuestionId() != null) {
                doc.append("questionId", step.getQuestionId());
            }
            if (step.getAnswer() != null) {
                doc.append("answer", step.getAnswer());
            }
            if (step.getNotes() != null) {
                doc.append("notes", step.getNotes());
            }
            if (step.getAttachmentIds() != null) {
                doc.append("attachmentIds", step.getAttachmentIds());
            }
            if (step.getAnsweredAt() != null) {
                doc.append("answeredAt", Date.from(step.getAnsweredAt()));
            }
            return doc;
        }).collect(Collectors.toList());
    }

    private List<CaseStep> mapStepsFromDocuments(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        return docs.stream()
                .map(this::mapStep)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private CaseStep mapStep(Document doc) {
        if (doc == null) {
            return null;
        }
        return CaseStep.builder()
                .questionId(doc.getString("questionId"))
                .answer(doc.getString("answer"))
                .notes(doc.getString("notes"))
                .attachmentIds(doc.getList("attachmentIds", String.class))
                .answeredAt(toInstant(doc.getDate("answeredAt")))
                .build();
    }

    private Instant toInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }

    private boolean stepsEqual(List<CaseStep> a, List<CaseStep> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            CaseStep left = a.get(i);
            CaseStep right = b.get(i);
            if (!Objects.equals(normalize(left.getQuestionId()), normalize(right.getQuestionId()))) {
                return false;
            }
            if (!Objects.equals(normalize(left.getAnswer()), normalize(right.getAnswer()))) {
                return false;
            }
        }
        return true;
    }

    private double scoreCase(List<CaseStep> reference, List<CaseStep> candidate) {
        if (reference.isEmpty() || candidate.isEmpty()) {
            return 0d;
        }
        Map<String, String> referenceMap = reference.stream()
                .filter(step -> step.getQuestionId() != null)
                .filter(step -> step.getAnswer() != null)
                .collect(Collectors.toMap(
                        step -> normalize(step.getQuestionId()),
                        step -> normalize(step.getAnswer()),
                        (first, second) -> second,
                        java.util.LinkedHashMap::new));

        Map<String, String> candidateMap = candidate.stream()
                .filter(step -> step.getQuestionId() != null)
                .filter(step -> step.getAnswer() != null)
                .collect(Collectors.toMap(
                        step -> normalize(step.getQuestionId()),
                        step -> normalize(step.getAnswer()),
                        (first, second) -> second,
                        java.util.LinkedHashMap::new));

        long matches = referenceMap.entrySet().stream()
                .filter(entry -> Objects.equals(entry.getValue(), candidateMap.get(entry.getKey())))
                .count();

        int denominator = Math.max(referenceMap.size(), candidateMap.size());
        return denominator == 0 ? 0d : (double) matches / denominator;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private record CaseScore(Case caseData, double score) {}
}
