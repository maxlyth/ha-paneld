#!/usr/bin/env bash
# Mutation battery for issue #90 (custom Home Assistant dashboard views).
#
# Each entry in mutations.json breaks ONE property this feature depends on and names the assertion that
# must go red for it. A kill is credited only when that NAMED test fails — a red count elsewhere proves
# the suite noticed something, not that the property is covered. Verdicts:
#
#   KILLED               the named assertion failed, as intended
#   SURVIVED             the mutation applied and everything still passed  -> a real coverage hole
#   WRONG-TEST-RED       something failed, but not the named assertion     -> the claim is unproven
#   MUTATION-ABSENT      the pattern did not match                         -> not evidence, fix the anchor
#   MUTATION-BROKE-BUILD compilation/syntax failed                         -> not evidence, fix the mutation
#   CONTROL-OK           the no-op control ran green, so the plumbing reports honestly
#
# Runs in a THROWAWAY checkout: a battery killed midway must never leave a mutation in a real worktree.
#
#   bash tools/issue-90/mutation-battery.sh [name-substring]
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FILTER="${1:-}"
CHROME="${CHROME:-/usr/bin/chromium}"
WORK="$(mktemp -d /tmp/issue90-battery-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "source worktree : $SRC"
echo "battery checkout: $WORK"
# Copy the working tree (including uncommitted work — that is what is under test), minus build output.
tar -C "$SRC" --exclude=build --exclude=.gradle --exclude=.git --exclude=node_modules -cf - . \
  | tar -C "$WORK" -xf -
mkdir -p "$WORK/test"
cp -r "$SRC/test/node_modules" "$WORK/test/node_modules"

python3 - "$WORK" "$FILTER" "$CHROME" <<'PY'
import json, os, re, subprocess, sys

work, filt, chrome = sys.argv[1], sys.argv[2], sys.argv[3]
mutations = json.load(open(os.path.join(work, "tools/issue-90/mutations.json")))
results = []

def run(cmd, cwd, timeout=1500):
    return subprocess.run(cmd, cwd=cwd, shell=True, capture_output=True, text=True, timeout=timeout)

# A saturated machine fails in ways that look exactly like a broken mutation. Twenty-odd sequential
# Gradle and Chromium launches will produce these, and reporting one as MUTATION-BROKE-BUILD (or as a
# red control) is worse than reporting nothing: it invites a real hole to be waved through as noise.
INFRA = re.compile(
    r"OutOfMemoryError|GC overhead limit|Could not (?:start|connect to|reserve)|"
    r"Daemon (?:disappeared|stopped)|Unable to start the daemon|Timeout waiting to lock|"
    r"Cannot allocate memory|Killed|JVM back-end|browserType\.launch",
    re.I,
)

def kotlin_verdict(res, named):
    out = res.stdout + res.stderr
    if re.search(r"^e: |Compilation error|Unresolved reference", out, re.M):
        # A genuine compile break names the mutated file; infrastructure noise does not.
        return ("MUTATION-BROKE-BUILD", out) if not INFRA.search(out) else ("INFRA-FAILURE", out)
    if INFRA.search(out):
        return "INFRA-FAILURE", out
    reds = re.findall(r"^(\S+) > (\S+) FAILED", out, re.M)
    reds += [(c, m) for c, m in re.findall(r"^(\S+) > (.+) FAILED$", out, re.M)]
    if not reds:
        return "SURVIVED", out
    if any(named in m or named in c for c, m in reds):
        return "KILLED", out
    return "WRONG-TEST-RED", out

def js_verdict(res, named):
    out = res.stdout + res.stderr
    if "SyntaxError" in out or "Cannot find module" in out:
        return "MUTATION-BROKE-BUILD", out
    failed = re.findall(r"^not ok \d+ - (.+)$", out, re.M)
    if not failed:
        return "SURVIVED", out
    if any(named in f for f in failed):
        return "KILLED", out
    # Every red test is a timeout and none is the named one: the machine was too busy to decide.
    timeouts = len(re.findall(r"Timeout \d+m?s exceeded", out))
    if timeouts >= len(failed):
        return "INFRA-FAILURE", out
    return "WRONG-TEST-RED", out

for m in mutations:
    if filt and filt not in m["name"]:
        continue
    path = os.path.join(work, m["file"])
    original = open(path, encoding="utf-8").read()
    applied = original.replace(m["find"], m["replace"], 1)
    is_control = m.get("expect") == "control"

    if applied == original and not is_control:
        results.append((m["name"], "MUTATION-ABSENT", "pattern did not match " + m["file"]))
        print(f"{m['name']:46s} MUTATION-ABSENT", flush=True)
        continue

    open(path, "w", encoding="utf-8").write(applied)
    try:
        if m["suite"] == "kotlin":
            res = run(
                f"./gradlew :app:testDebugUnitTest --console=plain --tests '{m['gradleFilter']}'",
                work,
            )
            verdict, out = kotlin_verdict(res, m["asserts"])
        else:
            res = run(
                # Only this feature's tests. The broader 'dashboard' pattern also dragged in three heavy
                # card-wall layout tests per mutation, which was most of the machine contention.
                f"CHROME={chrome} node --test --test-name-pattern='[Cc]ustom|dashboard picker fits|unknown dashboard|never refuses a dashboard route' browser-behavior.test.mjs",
                os.path.join(work, "test"),
            )
            verdict, out = js_verdict(res, m["asserts"])
    finally:
        open(path, "w", encoding="utf-8").write(original)

    if is_control:
        verdict = "CONTROL-OK" if verdict == "SURVIVED" else "CONTROL-BROKEN:" + verdict
    results.append((m["name"], verdict, m["asserts"]))
    print(f"{m['name']:46s} {verdict}", flush=True)
    # A verdict that is not a clean kill has to be inspectable, or "probably noise" becomes the way a
    # real coverage hole gets waved through.
    if verdict not in ("KILLED", "CONTROL-OK"):
        tail = "\n".join(out.strip().splitlines()[-25:])
        print(f"    ---- {m['name']} output tail ----\n{tail}\n    ---- end ----", flush=True)

print("\n---- battery summary ----")
killed = sum(1 for _, v, _ in results if v == "KILLED")
graded = [r for r in results if not r[1].startswith("CONTROL")]
for name, verdict, note in results:
    print(f"  {verdict:22s} {name}")
print(f"\n{killed}/{len(graded)} killed, {len(results)} verdicts")
infra = [r for r in results if "INFRA-FAILURE" in r[1]]
if infra:
    print(f"{len(infra)} verdict(s) undecided on a saturated machine — re-run those names alone:")
    for name, _, _ in infra:
        print(f"  bash tools/issue-90/mutation-battery.sh {name}")
bad = [r for r in results if r[1] not in ("KILLED", "CONTROL-OK")]
sys.exit(1 if bad else 0)
PY
