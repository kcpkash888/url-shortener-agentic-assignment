package com.example.urlshortener.orchestrator.agents;

import com.example.urlshortener.orchestrator.Node;
import com.example.urlshortener.orchestrator.SharedContext;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Requirement-understanding agent. Flags vague/underspecified asks using a
 * small trigger-term table, generates concrete candidate interpretations for
 * each vague term found, scores them on an impact/effort heuristic, and
 * picks a winner -- recording the rejected alternatives and why they lost.
 * Direct port of orchestrator/agents/requirements_agent.py.
 */
public final class RequirementsAgent {
    private RequirementsAgent() {}

    public record Candidate(String interpretation, int impact, int effort, String notes) {
        double score() { return impact * 2.0 - effort; }
    }

    private static final Map<String, List<Candidate>> VAGUE_TERM_INTERPRETATIONS = Map.of(
            "safer", List.of(
                    new Candidate(
                            "Reject URLs that point at known-malicious or disallowed domains, and reserve "
                                    + "system-critical short codes (api, health, admin) so they cannot be squatted.",
                            5, 2,
                            "Directly closes an abuse vector (phishing redirect, route hijack) with a small, "
                                    + "well-scoped change to input validation."),
                    new Candidate(
                            "Add authentication and per-user link ownership so only the creator can edit or "
                                    + "delete a link.",
                            4, 5,
                            "Real hardening, but requires an auth subsystem that doesn't exist yet -- out of "
                                    + "proportion to a single ambiguous ask."),
                    new Candidate(
                            "Scan link targets against a live third-party threat-intelligence API before "
                                    + "allowing creation.",
                            4, 4,
                            "Effective but introduces an external dependency, latency on the write path, and a "
                                    + "new vendor/cost surface -- not justified without an explicit requirement for it.")
            ),
            "faster", List.of(
                    new Candidate(
                            "Serve hot redirect lookups from a cache instead of hitting the database on every request.",
                            4, 2,
                            "Redirect is the highest-traffic path; a TTL cache removes most read load cheaply."),
                    new Candidate(
                            "Move the database from SQLite to a distributed store.",
                            3, 5,
                            "Real scaling lever eventually, but SQLite is not the bottleneck at current scale -- "
                                    + "premature for this ask.")
            ),
            "robust", List.of(
                    new Candidate(
                            "Add bounded retries and input validation at the API boundary so a bad client "
                                    + "request or a transient failure doesn't corrupt state.",
                            4, 2,
                            "Addresses the most common source of production incidents for a service like this.")
            ),
            "scalable", List.of(
                    new Candidate(
                            "Ensure the redirect path is cache-first and stateless so it can be horizontally "
                                    + "replicated behind a load balancer.",
                            4, 3,
                            "Cheapest change that removes the biggest scaling blocker without a full rewrite.")
            )
    );

    private static final Pattern ACCEPTANCE_CRITERIA_HINTS =
            Pattern.compile("\\b(must|should|shall|when .* then|endpoint|field|status code|schema)\\b", Pattern.CASE_INSENSITIVE);

    private static List<String> detectVagueTerms(String raw) {
        String lowered = raw.toLowerCase();
        return VAGUE_TERM_INTERPRETATIONS.keySet().stream()
                .filter(lowered::contains).sorted().toList();
    }

    public static Map<String, Object> normalize(SharedContext ctx, Node node) {
        String raw = ctx.rawRequest;
        List<String> vagueTerms = detectVagueTerms(raw);
        boolean hasAcceptanceCriteria = ACCEPTANCE_CRITERIA_HINTS.matcher(raw).find();
        boolean ambiguityDetected = !vagueTerms.isEmpty() && !hasAcceptanceCriteria;

        if (!ambiguityDetected) {
            ctx.recordDecision(
                    "requirements", node.id,
                    "Treated request as already concrete; proceeding without a clarification step.",
                    "No vague trigger terms were found, or the request already states explicit acceptance "
                            + "criteria ('" + raw + "')."
            );
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("raw_request", raw);
            out.put("normalized_requirement", raw);
            out.put("ambiguity_detected", false);
            out.put("clarifying_questions", List.of());
            out.put("interpretations", List.of());
            out.put("chosen_interpretation", raw);
            out.put("rejected_interpretations", List.of());
            return out;
        }

        LinkedHashMap<String, Candidate> unique = new LinkedHashMap<>();
        for (String term : vagueTerms) {
            for (Candidate c : VAGUE_TERM_INTERPRETATIONS.get(term)) {
                unique.putIfAbsent(c.interpretation(), c);
            }
        }
        List<Candidate> ranked = unique.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .toList();
        Candidate chosen = ranked.get(0);
        List<Candidate> rejected = ranked.subList(1, ranked.size());

        List<String> clarifyingQuestions = vagueTerms.stream()
                .map(t -> "When you said '" + t + "', did you mean the specific change below, or something else?")
                .toList();

        List<String> rejectedSummaries = rejected.stream()
                .map(c -> c.interpretation() + " (impact=" + c.impact() + ", effort=" + c.effort() + ") -- rejected: " + c.notes())
                .collect(Collectors.toList());

        ctx.recordDecision(
                "requirements", node.id,
                "Normalized ambiguous request into: " + chosen.interpretation(),
                "Detected vague term(s) " + vagueTerms + " with no explicit acceptance criteria. Generated "
                        + unique.size() + " candidate interpretation(s), scored each on impact (1-5) minus "
                        + "effort (1-5) x weighting, and selected the highest-scoring option because: " + chosen.notes(),
                rejectedSummaries
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raw_request", raw);
        out.put("normalized_requirement", chosen.interpretation());
        out.put("ambiguity_detected", true);
        out.put("vague_terms", vagueTerms);
        out.put("clarifying_questions", clarifyingQuestions);
        out.put("interpretations", unique.values().stream().map(Candidate::interpretation).toList());
        out.put("chosen_interpretation", chosen.interpretation());
        out.put("rejected_interpretations", rejected.stream().map(Candidate::interpretation).toList());
        return out;
    }
}
