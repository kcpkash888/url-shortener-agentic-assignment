"""Requirement-understanding agent.

Runs on every scenario. It flags vague/underspecified asks using a small
trigger-term table, generates concrete candidate interpretations for each
vague term found, scores them on an impact/effort heuristic, and picks a
winner -- recording the rejected alternatives and the reason they lost. For
an already-concrete ask, it still runs (and records why it judged the ask
unambiguous) so the decision lineage is complete for every node, not just
the interesting ones.
"""
import re

# trigger term -> candidate interpretations, each with a rough impact/effort
# score (1-5, higher impact/lower effort is better) and engineering notes.
VAGUE_TERM_INTERPRETATIONS: dict[str, list[dict]] = {
    "safer": [
        {
            "interpretation": "Reject URLs that point at known-malicious or disallowed domains, and "
            "reserve system-critical short codes (api, health, admin) so they cannot be squatted.",
            "impact": 5, "effort": 2,
            "notes": "Directly closes an abuse vector (phishing redirect, route hijack) with a small, "
            "well-scoped change to input validation.",
        },
        {
            "interpretation": "Add authentication and per-user link ownership so only the creator can "
            "edit or delete a link.",
            "impact": 4, "effort": 5,
            "notes": "Real hardening, but requires an auth subsystem that doesn't exist yet -- out of "
            "proportion to a single ambiguous ask.",
        },
        {
            "interpretation": "Scan link targets against a live third-party threat-intelligence API "
            "before allowing creation.",
            "impact": 4, "effort": 4,
            "notes": "Effective but introduces an external dependency, latency on the write path, and a "
            "new vendor/cost surface -- not justified without an explicit requirement for it.",
        },
    ],
    "faster": [
        {
            "interpretation": "Serve hot redirect lookups from a cache instead of hitting SQLite on "
            "every request.",
            "impact": 4, "effort": 2,
            "notes": "Redirect is the highest-traffic path; a TTL cache removes most read load cheaply.",
        },
        {
            "interpretation": "Move the database from SQLite to a distributed store.",
            "impact": 3, "effort": 5,
            "notes": "Real scaling lever eventually, but SQLite is not the bottleneck at current scale -- "
            "premature for this ask.",
        },
    ],
    "robust": [
        {
            "interpretation": "Add bounded retries and input validation at the API boundary so a bad "
            "client request or a transient failure doesn't corrupt state.",
            "impact": 4, "effort": 2,
            "notes": "Addresses the most common source of production incidents for a service like this.",
        },
    ],
    "scalable": [
        {
            "interpretation": "Ensure the redirect path is cache-first and stateless so it can be "
            "horizontally replicated behind a load balancer.",
            "impact": 4, "effort": 3,
            "notes": "Cheapest change that removes the biggest scaling blocker (in-process rate-limit "
            "and cache state) without a full rewrite.",
        },
    ],
}

ACCEPTANCE_CRITERIA_HINTS = re.compile(
    r"\b(must|should|shall|when .* then|endpoint|field|status code|schema)\b", re.IGNORECASE
)


def _detect_vague_terms(raw: str) -> list[str]:
    lowered = raw.lower()
    return sorted(t for t in VAGUE_TERM_INTERPRETATIONS if t in lowered)


def _score(candidate: dict) -> float:
    return candidate["impact"] * 2 - candidate["effort"]


def normalize(ctx, node) -> dict:
    raw = ctx.raw_request
    vague_terms = _detect_vague_terms(raw)
    has_acceptance_criteria = bool(ACCEPTANCE_CRITERIA_HINTS.search(raw))
    ambiguity_detected = bool(vague_terms) and not has_acceptance_criteria

    if not ambiguity_detected:
        ctx.record_decision(
            stage="requirements",
            node_id=node.id,
            summary="Treated request as already concrete; proceeding without a clarification step.",
            rationale=(
                "No vague trigger terms were found, or the request already states explicit "
                f"acceptance criteria ('{raw}')."
            ),
        )
        return {
            "raw_request": raw,
            "normalized_requirement": raw,
            "ambiguity_detected": False,
            "clarifying_questions": [],
            "interpretations": [],
            "chosen_interpretation": raw,
            "rejected_interpretations": [],
        }

    candidates: list[dict] = []
    for term in vague_terms:
        candidates.extend(VAGUE_TERM_INTERPRETATIONS[term])
    # de-duplicate by interpretation text while preserving first occurrence
    seen = set()
    unique_candidates = []
    for c in candidates:
        if c["interpretation"] not in seen:
            seen.add(c["interpretation"])
            unique_candidates.append(c)

    ranked = sorted(unique_candidates, key=_score, reverse=True)
    chosen = ranked[0]
    rejected = ranked[1:]

    clarifying_questions = [
        f"When you said '{term}', did you mean the specific change below, or something else?"
        for term in vague_terms
    ]

    ctx.record_decision(
        stage="requirements",
        node_id=node.id,
        summary=f"Normalized ambiguous request into: {chosen['interpretation']}",
        rationale=(
            f"Detected vague term(s) {vague_terms} with no explicit acceptance criteria. Generated "
            f"{len(unique_candidates)} candidate interpretation(s), scored each on impact (1-5) minus "
            f"effort (1-5) x weighting, and selected the highest-scoring option because: {chosen['notes']}"
        ),
        alternatives_considered=[
            f"{c['interpretation']} (impact={c['impact']}, effort={c['effort']}) -- rejected: {c['notes']}"
            for c in rejected
        ],
    )

    return {
        "raw_request": raw,
        "normalized_requirement": chosen["interpretation"],
        "ambiguity_detected": True,
        "vague_terms": vague_terms,
        "clarifying_questions": clarifying_questions,
        "interpretations": [c["interpretation"] for c in unique_candidates],
        "chosen_interpretation": chosen["interpretation"],
        "rejected_interpretations": [c["interpretation"] for c in rejected],
    }
