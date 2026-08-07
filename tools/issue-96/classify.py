#!/usr/bin/env python3
"""Classify one battery run: did the NAMED assertion go red?

Kotlin: classify.py kotlin <named_test> <results-dir>   (JUnit XML from the focused Gradle run)
Browser: classify.py browser <named_test> <tap-file>    (node --test TAP output)

Prints exactly one verdict: KILLED, SURVIVED, WRONG-TEST-RED, or CONTROL-OK semantics are the
driver's job — this tool only answers for the named test. A kill is credited ONLY when the named
test itself failed, because a red run alone never proves the right assertion fired.
"""

import re
import sys
from pathlib import Path
from xml.etree import ElementTree


def kotlin(named: str, results: Path) -> str:
    failed, named_failed = [], False
    for report in results.glob("TEST-*.xml"):
        for case in ElementTree.parse(report).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(case.get("name", ""))
                if case.get("name") == named:
                    named_failed = True
    if named_failed:
        return "KILLED"
    return "WRONG-TEST-RED" if failed else "SURVIVED"


def browser(named: str, tap: Path) -> str:
    text = tap.read_text(errors="replace")
    not_ok = re.findall(r"^not ok \d+ - (.+?)(?: #.*)?$", text, re.M)
    if any(name.strip() == named for name in not_ok):
        return "KILLED"
    return "WRONG-TEST-RED" if not_ok else "SURVIVED"


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    mode, named, target = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    print(kotlin(named, target) if mode == "kotlin" else browser(named, target))
    return 0


if __name__ == "__main__":
    sys.exit(main())
