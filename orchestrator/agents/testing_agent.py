"""Testing agent: actually shells out to pytest and reports a real pass/fail
result plus captured output -- this is a genuine test-execution gate, not a
simulated one. The `tests_must_pass` exit policy (orchestrator/policy.py) is
what turns a failing result into a blocked node.
"""
import subprocess
import sys


def run_tests(ctx, node, test_path: str, project_root: str, python_exe: str | None = None) -> dict:
    exe = python_exe or sys.executable
    proc = subprocess.run(
        [exe, "-m", "pytest", test_path, "-q"],
        cwd=project_root,
        capture_output=True,
        text=True,
        timeout=120,
    )
    passed = proc.returncode == 0
    summary_line = next(
        (line for line in reversed(proc.stdout.splitlines()) if line.strip()), proc.stdout[-200:] if proc.stdout else ""
    )

    ctx.record_decision(
        stage=node.stage,
        node_id=node.id,
        summary=f"pytest {test_path} {'passed' if passed else 'failed'}: {summary_line}",
        rationale="Ran the real test suite as an exit gate; downstream docs/release nodes are policy-blocked "
        "from proceeding on a failing result.",
    )

    return {
        "passed": passed,
        "returncode": proc.returncode,
        "summary": summary_line,
        "stdout_tail": proc.stdout[-3000:],
        "stderr_tail": proc.stderr[-1500:],
    }
