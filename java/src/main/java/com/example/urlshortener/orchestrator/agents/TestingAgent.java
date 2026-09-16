package com.example.urlshortener.orchestrator.agents;

import com.example.urlshortener.orchestrator.Node;
import com.example.urlshortener.orchestrator.SharedContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Testing agent: actually shells out to Maven's test goal and reports a real
 * pass/fail result plus captured output -- Maven compiles before testing, so
 * this is also the compile gate for the whole pipeline (a distinctly-Java
 * failure mode Python doesn't have). Direct port of
 * orchestrator/agents/testing_agent.py.
 */
public final class TestingAgent {
    private TestingAgent() {}

    private static final Pattern SUMMARY_RE =
            Pattern.compile("Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: \\d+.*");

    public static Map<String, Object> runTests(SharedContext ctx, Node node, Path projectRoot) {
        String mvnExe = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        ProcessBuilder pb = new ProcessBuilder(mvnExe, "-o", "test");
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        String output;
        int exitCode;
        try {
            Process proc = pb.start();
            output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new RuntimeException("mvn test timed out after 180s");
            }
            exitCode = proc.exitValue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        boolean passed = exitCode == 0;
        Matcher m = SUMMARY_RE.matcher(output);
        String summaryLine = null;
        while (m.find()) summaryLine = m.group(); // keep the last (aggregate) match
        if (summaryLine == null) {
            summaryLine = passed ? "BUILD SUCCESS" : "BUILD FAILURE (see stdout_tail for details)";
        }

        ctx.recordDecision(
                node.stage, node.id,
                "mvn test " + (passed ? "passed" : "failed") + ": " + summaryLine,
                "Ran the real build+test as an exit gate (Maven compiles before testing, so this also catches "
                        + "compile errors); downstream docs/release nodes are policy-blocked from proceeding on a "
                        + "failing result."
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", passed);
        out.put("exit_code", exitCode);
        out.put("summary", summaryLine);
        out.put("stdout_tail", output.length() > 4000 ? output.substring(output.length() - 4000) : output);
        return out;
    }
}
