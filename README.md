# HelpOS Backend

Refugee families arriving in Switzerland often sit down with a volunteer lawyer
who has minutes to grasp their story and locate the right pathway through
Swiss, EU, and Dublin regulations. Doing that manually means leafing through
statutes, hunting for precedent, building timelines, and drafting advice—all
while the family waits. HelpOS transforms that scramble into a guided, humane
interview that maps each answer to the law and highlights the next legal steps.

This repository contains the Spring Boot service that powers the platform. The
backend exposes REST APIs for structuring legal topics, building guided forms,
running interactive interviews, and surfacing similar closed cases using MongoDB
for persistence and Neo4j for relationship tracking.

---

## What It Does

- **Topic & Form Management** – Create and version the interview flows that
  legal specialists design for specific subject areas.
- **Guided Question Trees** – Persist branching questions with answer options,
  sources, and optional follow-up links to keep interviews consistent.
- **Case Runs & Aggregation** – Capture each consultation as a run, close it
  with outcomes and notes, and fold matching runs into aggregated “cases” with
  frequency tracking.
- **Similarity Retrieval** – Score runs against historic cases using shared
  question/answer pairs so lawyers can reference precedent instantly.
- **Neo4j Mirror** – Sync every topic, form, and question into a graph model for
  downstream analytics or exploratory search.

---

## Tech Stack

| Layer        | Technology                                               |
|--------------|-----------------------------------------------------------|
| Language     | Java 21 (compatible with Java 17+)                       |
| Framework    | Spring Boot 3 (Web, Data MongoDB, Data Neo4j)            |
| Datastores   | MongoDB Atlas (document data), Neo4j Aura (graph)        |
| Build Tool   | Gradle with Spring Dependency Management                 |
| Tooling      | Lombok, JUnit (tests currently disabled), Docker-friendly |
| Data Seeding | `populate.py` (Python 3, `requests`)                     |

---

## Getting Started

### Prerequisites

- JDK 21 (or 17+) on your PATH
- Python 3.9+ if you plan to run the seeding script
- MongoDB and Neo4j instances (connection URIs configurable)

### Configure Connections

Default values live in `src/main/resources/application.properties`. Override
them with environment variables or a profile-specific properties file:

```properties
helpos.mongo.uri=mongodb://user:pass@localhost:27017/helpos
helpos.mongo.database=helpos
helpos.neo4j.uri=neo4j://localhost:7687
helpos.neo4j.username=neo4j
helpos.neo4j.password=secret
```

### Run the API

```bash
./gradlew bootRun       # macOS / Linux
gradlew.bat bootRun     # Windows
```

The service starts on `http://localhost:8080/`.

---

## API Reference

The core resources are Topics → Forms → Questions → Runs → Cases. Paths are
relative to `/topics/{topicId}/forms/{formId}` unless otherwise noted.

### Topics
- `GET /topics` – List every topic. Response fields: `id`, `name`,
  `description`.
- `POST /topics` – Create a topic. Body:
  ```json
  { "name": "Health & Medical Protection", "description": "..." }
  ```
  Mirrors the topic as a Neo4j node.
- `GET /topics/{topicId}` – Fetch a single topic document.

### Forms
- `GET /topics/{topicId}/forms` – Return all forms below the topic. Each form
  contains `id`, `title`, `description`, `version`, `active`, `questionIds`,
  `tags`.
- `POST /topics/{topicId}/forms` – Create a form or new version. Body fields:
  - `title` (string, required)
  - `description` (string, optional)
  - `version` (string, optional)
  - `tags` (array of strings, optional)
  - `questionIds` (array of question IDs, optional)
  - `previousVersionId` (string, optional; marks previous version inactive)
- `GET /topics/{topicId}/forms/{formId}` – Retrieve metadata for a single form.

### Questions (guided only)
- `GET /topics/{topicId}/forms/{formId}/questions` – List questions in order.
  Response includes `answerOptions`, `answerType`, `source`, `tags`.
- `POST /topics/{topicId}/forms/{formId}/questions` – Create a root-level
  question. Required payload fields:
  - `text` – Question prompt.
  - `source` – Single URL string pointing to the controlling law.
  - `answerType` – `RADIO`, `CHECKBOX`, etc.
  - `answerOptions` – Array of objects with:
    - `id` (stable identifier)
    - `label` (display text)
    - `terminal` (boolean)
    - optional `nextQuestionId` (links to follow-up)
    - optional `legalReference` (inline citation text)
  - Optional `tags` array.
- `POST /topics/{topicId}/forms/{formId}/questions/{parentQuestionId}` – Add a
  subquestion branching from a parent answer. Body must include
  `parentAnswerId` to identify the option that triggers the new question. The
  endpoint reuses the same payload structure as above.
- `GET /topics/{topicId}/forms/{formId}/questions/{questionId}` – Fetch a single
  question by ID.
- `GET /topics/{topicId}/forms/{formId}/questions/{questionId}/children` – List
  immediate follow-up questions.

### Runs (live interviews)
- `POST /topics/{topicId}/forms/{formId}/runs`
  - Purpose: start a new guided interview.
  - Body fields: `profileId`, optional `status` (defaults to `RUNNING`),
    optional array of `steps`, optional `startedAt` timestamp.
  - Response: persisted run with generated `_id` and timestamps.
- `GET /topics/{topicId}/forms/{formId}/runs/{runId}` – Get the run plus all
  recorded steps (`questionId`, `answer`, `notes`, `attachmentIds`, `answeredAt`).
- `PUT /topics/{topicId}/forms/{formId}/runs/{runId}` – Update an in-progress
  run. Any subset of fields may be supplied; unknown fields are ignored.
- `POST /topics/{topicId}/forms/{formId}/runs/{runId}/close` – Close the run.
  Rules:
  - At least one step must exist (either already stored or supplied in the body).
  - `outcome` must be non-empty (taken from the body or the stored run).
  - Optional `closureNotes` capture narrative findings.
  - Marks the run as `COMPLETED`, writes `closedAt`, and upserts an aggregated
    case (see below).
- `GET /topics/{topicId}/forms/{formId}/runs/{runId}/similar` – Compare the run
  with historical cases. Returns up to 10 matches ordered by ascending count of
  shared question/answer pairs, breaking ties with `frequency` (descending).

### Cases (aggregated history)
- `GET /topics/{topicId}/forms/{formId}/cases` – List closed cases, sorted
  by descending `frequency`. Each entry contains:
  - `id`, `title`, `description`, `status`
  - `outcome`, `closureNotes`, `formVersion`
  - `steps` (final snapshot), `answeredQuestionIds`
  - `createdAt`, `completedAt`, `frequency`, `profileId`
- `GET /topics/{topicId}/forms/{formId}/cases/{caseId}` – Retrieve a single
  case document.

---

## Database Seeds

Kick-start the system with curated topics, forms, and guided questions:

```bash
python populate.py
# or
HELPOS_BASE_URL=https://staging.api.helpos.org python populate.py
```

The script is idempotent. It reuses existing topics/forms/questions when their
structure matches, and fails fast if guided answer options drift from the
expected configuration.

---

## Project Structure

```
src/main/java/ch/helpos/backend/
├── config/            # Mongo & web configuration
├── controller/        # REST controllers for topics, forms, questions, cases
├── models/            # MongoDB documents (Lombok-powered)
└── HelpOsBackendApplication.java

src/main/resources/
└── application.properties

populate.py            # Idempotent data seeder (guided questions only)
```

---

## Development Notes

- Tests are currently disabled in `build.gradle`. Re-enable by removing
  `tasks.withType(Test) { enabled = false }` and adding fixtures.
- All endpoints expect MongoDB ObjectId strings for path parameters.
- Every topic, form, and question is mirrored into Neo4j; ensure your database
  credentials allow Bolt+TLS connections.
- Similarity scoring uses the set of answered `questionId` plus normalized
  `answer` values. Answers left blank are ignored.

---

HelpOS is distributed under the [MIT License](./LICENSE). Questions or ideas?
Open an issue and help us speed critical legal support to the people who need it
most.
