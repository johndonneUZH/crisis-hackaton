#!/usr/bin/env python3
"""
Populate the HelpOS backend with interview topics, forms, and guided questions
that align with the current domain models. The script is idempotent: it reuses
existing documents (matched by name/title/text) and only creates what is missing.

Set HELPOS_BASE_URL if the API is not running on http://localhost:8080.
"""

from __future__ import annotations

import os
import sys
from typing import Dict, Iterable, List, Optional

import requests

BASE_URL = os.getenv("HELPOS_BASE_URL", "http://localhost:8080")

SESSION = requests.Session()
SESSION.headers.update({"Accept": "application/json", "Content-Type": "application/json"})


def option(
    option_id: str,
    label: str,
    *,
    terminal: bool = False,
    next_question_id: Optional[str] = None,
    legal_reference: Optional[str] = None,
) -> Dict[str, object]:
    """Create a guided answer option structure compatible with the backend."""
    payload: Dict[str, object] = {"id": option_id, "label": label, "terminal": terminal}
    if next_question_id:
        payload["nextQuestionId"] = next_question_id
    if legal_reference:
        payload["legalReference"] = legal_reference
    return payload


INTERVIEW_DATA: List[Dict[str, object]] = [
    {
        "topic": {
            "name": "Jurisdiction (Dublin Competence Check)",
            "description": "Ensure Dublin III criteria are reviewed to confirm Swiss competence.",
            "tags": ["jurisdiction", "dublin"],
        },
        "forms": [
            {
                "form": {
                    "title": "Verify Switzerland's responsibility to hear the claim (Stage: Preparatory Phase)",
                    "description": "Checklist for assessing whether Switzerland must examine the claim under Dublin rules.",
                    "version": "1.0",
                    "tags": ["jurisdiction", "dublin", "preparatory-phase"],
                },
                "questions": [
                    {
                        "text": "Has any family member previously resided, worked, or applied for asylum in an EU/EFTA (Dublin) state?",
                        "source": "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32013R0604",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("jurisdiction_q1_yes", "Yes – prior residence, employment, or asylum application in a Dublin state"),
                            option("jurisdiction_q1_no", "No – no Dublin-state history to report"),
                            option("jurisdiction_q1_unknown", "Unknown – verification pending"),
                        ],
                        "tags": ["jurisdiction", "dublin"],
                    },
                    {
                        "text": "Which visas, residence permits, or entry stamps from Dublin states can you surrender now?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_8",
                        "answerType": "CHECKBOX",
                        "answerOptions": [
                            option("jurisdiction_q1a_schengen", "Schengen (C) visa currently held"),
                            option("jurisdiction_q1a_residence", "National residence permit (L/B/C) from a Dublin state"),
                            option("jurisdiction_q1a_stamp", "Entry stamp only (no valid permit)"),
                            option("jurisdiction_q1a_none", "No travel or residence documents available"),
                            option("jurisdiction_q1a_other", "Other travel document (record separately)"),
                        ],
                        "tags": ["jurisdiction", "dublin"],
                    },
                    {
                        "text": "Were fingerprints or an asylum file ever created elsewhere (Eurodac hit)?",
                        "source": "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32013R0603",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("jurisdiction_q1b_yes", "Yes – Eurodac hit or asylum file confirmed"),
                            option("jurisdiction_q1b_no", "No – no Eurodac record"),
                            option("jurisdiction_q1b_unknown", "Unknown – awaiting confirmation"),
                        ],
                        "tags": ["jurisdiction", "dublin"],
                    },
                    {
                        "text": "Has SEM already issued or announced a Dublin transfer decision, and do grounds exist to request suspension within five days?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_107a",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("jurisdiction_q1c_yes_grounded", "Yes – decision issued and suspension grounds identified"),
                            option("jurisdiction_q1c_yes_no_ground", "Yes – decision issued without suspension grounds yet"),
                            option("jurisdiction_q1c_no", "No – no transfer decision issued or announced"),
                            option("jurisdiction_q1c_unknown", "Unknown – awaiting SEM communication"),
                        ],
                        "tags": ["jurisdiction", "dublin"],
                    },
                ],
            }
        ],
    },
    {
        "topic": {
            "name": "Health & Medical Protection (Mother)",
            "description": "Capture urgent medical needs to secure safeguards and removal deferrals.",
            "tags": ["health", "medical", "mother"],
        },
        "forms": [
            {
                "form": {
                    "title": "Immediate healthcare safeguards and removal deferral",
                    "description": "Identify urgent treatment needs and procedural safeguards for the mother.",
                    "version": "1.0",
                    "tags": ["health", "medical", "mother"],
                },
                "questions": [
                    {
                        "text": "Has the mother's urgent medical condition been formally disclosed with supporting medical proof?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_30a",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("health_q2_disclosed", "Yes – disclosed with supporting medical proof"),
                            option("health_q2_partial", "Partially – disclosed but supporting proof pending"),
                            option("health_q2_not_disclosed", "No – condition not yet disclosed"),
                        ],
                        "tags": ["health", "medical"],
                    },
                    {
                        "text": "Did SEM or a canton order or receive a medical assessment confirming treatment needs?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_30a",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("health_q2a_ordered", "Yes – assessment ordered and received"),
                            option("health_q2a_pending", "In progress – assessment requested but pending"),
                            option("health_q2a_not_ordered", "No – assessment not ordered"),
                        ],
                        "tags": ["health", "medical"],
                    },
                    {
                        "text": "Do the medical findings qualify as special circumstances requiring a longer departure deadline or suspension of removal?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_44",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("health_q2b_yes", "Yes – findings qualify as special circumstances"),
                            option("health_q2b_likely", "Likely – evidence still under evaluation"),
                            option("health_q2b_no", "No – criteria for special circumstances not met"),
                            option("health_q2b_unknown", "Unknown – insufficient information yet"),
                        ],
                        "tags": ["health", "medical"],
                    },
                    {
                        "text": "Has the responsible canton implemented medical care and insured the family under mandatory health cover?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_82",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("health_q2c_full", "Yes – care arranged and insurance activated"),
                            option("health_q2c_partial", "Partially – care arranged but insurance pending"),
                            option("health_q2c_not_yet", "No – safeguards not yet implemented"),
                        ],
                        "tags": ["health", "medical"],
                    },
                ],
            }
        ],
    },
    {
        "topic": {
            "name": "Child Education & Family Integration",
            "description": "Document schooling and integration measures that may support hardship arguments.",
            "tags": ["education", "integration", "family"],
        },
        "forms": [
            {
                "form": {
                    "title": "Schooling obligations and hardship documentation",
                    "description": "Track compulsory education, language support, and records for hardship assessments.",
                    "version": "1.0",
                    "tags": ["education", "integration", "family"],
                },
                "questions": [
                    {
                        "text": "Is the teenage child registered for compulsory schooling in the assigned canton?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/404/en#art_19",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("education_q3_registered", "Yes – registered and attending compulsory schooling"),
                            option("education_q3_in_progress", "In progress – enrolment initiated but incomplete"),
                            option("education_q3_not_registered", "No – child not yet registered"),
                        ],
                        "tags": ["education", "integration"],
                    },
                    {
                        "text": "Does the child need language support or special measures that must be recorded for integration planning?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/2007/758/en#art_53",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("education_q3a_yes", "Yes – language or special support documented"),
                            option("education_q3a_no", "No – standard schooling sufficient"),
                            option("education_q3a_pending", "Assessment pending"),
                        ],
                        "tags": ["education", "integration"],
                    },
                    {
                        "text": "Are school documents from the country of origin available to submit for placement and future hardship assessments?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/2007/758/en#art_30",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("education_q3b_available", "Yes – documents available for submission"),
                            option("education_q3b_partial", "Partially – some documents available"),
                            option("education_q3b_unavailable", "No – documents unavailable"),
                        ],
                        "tags": ["education", "integration"],
                    },
                    {
                        "text": "Has any interruption or exclusion from schooling been reported to cantonal migration authorities?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/2007/759/en#art_82e",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("education_q3c_reported", "Yes – interruption or exclusion reported"),
                            option("education_q3c_not_needed", "No – no interruptions to report"),
                            option("education_q3c_pending", "Needs follow-up – interruption occurred but not reported"),
                        ],
                        "tags": ["education", "integration"],
                    },
                ],
            }
        ],
    },
    {
        "topic": {
            "name": "Housing & Social Support (Basic Needs)",
            "description": "Clarify accommodation arrangements and financial responsibilities for the family.",
            "tags": ["housing", "support", "basic-needs"],
        },
        "forms": [
            {
                "form": {
                    "title": "Accommodation status and financial screening",
                    "description": "Record placement, financial duties, and vulnerability factors affecting housing.",
                    "version": "1.0",
                    "tags": ["housing", "support", "basic-needs"],
                },
                "questions": [
                    {
                        "text": "Where is the family housed now, and who bears current support duties?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_24",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("housing_q4_federal", "Federal asylum centre – SEM responsible for support"),
                            option("housing_q4_cantonal", "Cantonal accommodation – canton responsible"),
                            option("housing_q4_private", "Private or independent housing with oversight"),
                        ],
                        "tags": ["housing", "support"],
                    },
                    {
                        "text": "Does the family have assets that trigger the federal special charge to finance accommodation?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_85a",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("housing_q4a_yes", "Yes – assets trigger the federal special charge"),
                            option("housing_q4a_no", "No – no chargeable assets"),
                            option("housing_q4a_unknown", "Unknown – financial verification pending"),
                        ],
                        "tags": ["housing", "support"],
                    },
                    {
                        "text": "Has cantonal social assistance or emergency aid been requested, and what documentation was supplied?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_82",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("housing_q4b_submitted", "Yes – assistance requested with required documentation"),
                            option("housing_q4b_pending", "Application pending – documents being compiled"),
                            option("housing_q4b_not_requested", "No – assistance not requested"),
                        ],
                        "tags": ["housing", "support"],
                    },
                    {
                        "text": "Do health or vulnerability factors require prioritised housing arrangements under SEM directives?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/1999/358/en#art_24d",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("housing_q4c_required", "Yes – prioritised housing arrangements granted"),
                            option("housing_q4c_pending", "Request pending review"),
                            option("housing_q4c_not_required", "No – prioritised arrangements not required"),
                        ],
                        "tags": ["housing", "support"],
                    },
                ],
            }
        ],
    },
    {
        "topic": {
            "name": "Employment & Procedural Safeguards (Father)",
            "description": "Track employment restrictions, authorisations, and hardship groundwork for the father.",
            "tags": ["employment", "procedure", "father"],
        },
        "forms": [
            {
                "form": {
                    "title": "Employment rights, appeal strategy, hardship groundwork",
                    "description": "Monitor work eligibility, appeals, and documentation for hardship or temporary admission.",
                    "version": "1.0",
                    "tags": ["employment", "procedure", "father"],
                },
                "questions": [
                    {
                        "text": "Is the father seeking work, and is he aware of the employment ban while in a federal centre?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/2007/758/en#art_43",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("employment_q5_informed", "Yes – seeking work and aware of the federal centre employment ban"),
                            option("employment_q5_unaware", "Seeking work but unaware of the employment ban"),
                            option("employment_q5_not_seeking", "Not seeking work currently"),
                        ],
                        "tags": ["employment", "procedure"],
                    },
                    {
                        "text": "If transferred to a canton, has a work authorisation request been filed under cantonal competence?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/2007/758/en#art_43",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("employment_q5a_filed", "Yes – work authorisation request filed with the canton"),
                            option("employment_q5a_not_filed", "No – request not yet filed"),
                            option("employment_q5a_not_applicable", "Not applicable – still in federal asylum centre"),
                        ],
                        "tags": ["employment", "procedure"],
                    },
                    {
                        "text": "Do we need to prepare a hardship or temporary-admission dossier documenting schooling, medical needs, or social integration?",
                        "source": "https://www.fedlex.admin.ch/eli/cc/2007/758/en#art_30",
                        "answerType": "RADIO",
                        "answerOptions": [
                            option("employment_q5b_in_progress", "Yes – dossier preparation in progress"),
                            option("employment_q5b_planned", "Planned – dossier not yet started"),
                            option("employment_q5b_not_planned", "No – not pursuing hardship or temporary admission"),
                        ],
                        "tags": ["employment", "procedure"],
                    },
                ],
            }
        ],
    },
]


def ensure_topic(topic_def: Dict[str, object]) -> Dict[str, object]:
    resp = SESSION.get(f"{BASE_URL}/topics", timeout=10)
    resp.raise_for_status()
    for topic in resp.json():
        if topic.get("name") == topic_def["name"]:
            print(f"Reusing topic {topic['id']} ({topic['name']})")
            return topic

    payload = {"name": topic_def["name"], "description": topic_def.get("description")}
    resp = SESSION.post(f"{BASE_URL}/topics", json=payload, timeout=10)
    resp.raise_for_status()
    created = resp.json()
    print(f"Created topic {created['id']} ({created['name']})")
    return created


def ensure_form(topic_id: str, form_def: Dict[str, object]) -> Dict[str, object]:
    resp = SESSION.get(f"{BASE_URL}/topics/{topic_id}/forms", timeout=10)
    resp.raise_for_status()
    for form in resp.json():
        if form.get("title") == form_def["title"]:
            print(f"  Reusing form {form['id']} ({form['title']})")
            return form

    payload = {
        "title": form_def["title"],
        "description": form_def.get("description"),
        "version": form_def.get("version"),
        "tags": form_def.get("tags", []),
        "questionIds": [],
    }
    resp = SESSION.post(f"{BASE_URL}/topics/{topic_id}/forms", json=payload, timeout=10)
    resp.raise_for_status()
    created = resp.json()
    print(f"  Created form {created['id']} ({created['title']})")
    return created


def ensure_question(topic_id: str, form_id: str, question_def: Dict[str, object]) -> Dict[str, object]:
    resp = SESSION.get(f"{BASE_URL}/topics/{topic_id}/forms/{form_id}/questions", timeout=10)
    resp.raise_for_status()
    existing = resp.json()

    expected_type = question_def["answerType"]
    expected_options = question_def["answerOptions"]
    expected_option_labels = [opt["label"] for opt in expected_options]

    for question in existing:
        if question.get("text") == question_def["text"]:
            actual_type = question.get("answerType")
            actual_options = question.get("answerOptions") or []
            actual_labels = [opt.get("label") for opt in actual_options]
            if actual_type != expected_type or actual_labels != expected_option_labels:
                raise RuntimeError(
                    f"Question '{question_def['text']}' exists but its guided answers do not match. "
                    "Adjust or remove the existing question before re-running the seeder."
                )
            print(f"    Reusing question {question['id']} ({question_def['text']})")
            return question

    payload = {
        "text": question_def["text"],
        "source": question_def["source"],
        "answerType": expected_type,
        "answerOptions": expected_options,
        "tags": question_def.get("tags", []),
    }
    resp = SESSION.post(
        f"{BASE_URL}/topics/{topic_id}/forms/{form_id}/questions",
        json=payload,
        timeout=10,
    )
    resp.raise_for_status()
    created = resp.json()
    print(f"    Created question {created['id']} ({question_def['text']})")
    return created


def main() -> int:
    try:
        for bundle in INTERVIEW_DATA:
            topic = ensure_topic(bundle["topic"])
            for form_entry in bundle.get("forms", []):
                form = ensure_form(topic["id"], form_entry["form"])
                for question_def in form_entry.get("questions", []):
                    ensure_question(topic["id"], form["id"], question_def)
        print("Seeding complete.")
        return 0
    except requests.RequestException as exc:
        print(f"Error communicating with HelpOS backend: {exc}", file=sys.stderr)
        return 1
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

