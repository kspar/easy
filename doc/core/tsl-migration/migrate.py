#!/usr/bin/env python3
"""Rewrites old-model TSL specs against the collapsed model (EZ-1607).

    python3 migrate.py --export /tmp/tsl-export.jsonl --out ./migrated
    python3 explode.py --export /tmp/tsl-export.jsonl --verify ./migrated

Produces the same tree shape as explode.py, so the verifier can compare the two.

**The rule is behaviour preservation, not improvement.** Every mapping below reproduces what the
old compiler emitted and the old analyser computed, including one case where a test's feedback
messages contradict what it actually checks. Fixing that here would silently change a teacher's
exercise; it is reported instead.

Two mappings deserve their justification written down, because both look like approximations and
are not:

*Loop, try/except and return* had dedicated analyser targets and now become keyword checks.
Equivalent because the old booleans were themselves computed from AST node types, and
`KEYWORD_TO_AST_NODES` maps the same nodes to the same keywords:

    contains_loop_tv       For | While | comprehension     for -> {For, AsyncFor, comprehension}
                                                           while -> {While}
    contains_try_except_tv Try | ExceptHandler              try -> {Try}, except -> {ExceptHandler}
    contains_return_tv     Return                           return -> {Return}

`try` OR `except` rather than both, because a bare `try/finally` set the old flag. The only
divergence is `async for`, which now counts as a loop and did not before.

*calls_print* was `"print" in calls_function_names`. `ALL_OF_THESE` over `["print"]` is
`{"print"} <= calls_function_names`, and `NONE_OF_THESE` is the complement. Identical.
"""
import argparse
import json
import pathlib
import re
import shutil
import sys
from collections import Counter

MISSING_COMMA = re.compile(r'(["\d\]\}]|true|false|null)(\s*\n\s*)(")')

# Carried across unchanged. Dead or not, they are the teacher's data and not ours to drop.
BASE_FIELDS = ("id", "name", "pointsWeight", "visibleToUser", "inputs", "passedNext", "failedNext")

# GenericCheck has these three and GenericCheckLong does not. Emitting them into a collapsed test
# is a hard decode failure server-side, since kotlinx runs with ignoreUnknownKeys = false.
SHORT_ONLY = ("id", "elementsOrdered", "outputCategory")


def load_spec(content):
    """Parses the way production does — see parse_spec in explode.py for why this is not strict."""
    for attempt in (
        lambda s: json.loads(s),
        lambda s: json.loads(s, strict=False),
        lambda s: json.loads(MISSING_COMMA.sub(r"\1,\2\3", s), strict=False),
    ):
        try:
            return attempt(content)
        except json.JSONDecodeError:
            continue
    return None


def base_of(test):
    return {k: test[k] for k in BASE_FIELDS if k in test}


def to_long(check):
    """GenericCheck -> GenericCheckLong. CheckType's four values are all valid CheckTypeLong."""
    return {k: v for k, v in check.items() if k not in SHORT_ONLY}


def synth(bool_check, keywords, must):
    """A GenericCheckLong standing in for one of the retired boolean checks.

    `must` is the old `expected_value`, which the old compiler always emitted as `!mustNotX`.
    """
    if must:
        kind = "ALL_OF_THESE" if len(keywords) == 1 else "ANY_OF_THESE"
    else:
        kind = "NONE_OF_THESE"
    return {
        "checkType": kind,
        "expectedValue": list(keywords),
        "beforeMessage": bool_check.get("beforeMessage", ""),
        "passedMessage": bool_check.get("passedMessage", ""),
        "failedMessage": bool_check.get("failedMessage", ""),
    }


def calls(t, scope, target, check, *, function_name=None, class_name=None):
    return {
        **base_of(t), "type": "calls_test", "scope": scope, "targetType": target,
        "functionName": function_name, "className": class_name, "genericCheck": check,
    }


def contains(t, scope, check, *, what="KEYWORD_NO_ARG", arg=None, function_name=None, class_name=None):
    return {
        **base_of(t), "type": "contains_test", "scope": scope, "containsWhat": what,
        "containsWhatArg": arg, "functionName": function_name, "className": class_name,
        "genericCheck": check,
    }


def defines(t, scope, kind, check, *, function_name=None, class_name=None):
    values = check.get("expectedValue") or []
    return {
        **base_of(t), "type": "definition_test", "scopeType": scope,
        "definitionCheckType": kind,
        # Required and non-null but read by nothing — tiivad checks the expected values instead
        # (EZ-1742). Tracks the first of them so the generated test name still reads sensibly.
        "definitionCheckValue": values[0] if values else "",
        "superClassName": None, "functionName": function_name, "className": class_name,
        "genericCheck": check,
    }


def prop(t, which, bool_check, flag):
    return {
        **base_of(t), "type": "function_is_test", "functionName": t["functionName"],
        "functionProperty": which,
        "propertyCheck": {
            "mustHaveProperty": not bool_check.get(flag, False),
            "beforeMessage": bool_check.get("beforeMessage", ""),
            "passedMessage": bool_check.get("passedMessage", ""),
            "failedMessage": bool_check.get("failedMessage", ""),
        },
    }


def g(t):
    return t.get("genericCheck") or {}


# The 18 retired types the corpus actually contains. Anything not here and not already on the new
# model stops the migration rather than being guessed at.
MIGRATIONS = {
    # --- calls ------------------------------------------------------------------------------
    "program_calls_function_test":
        lambda t: calls(t, "PROGRAM", "FUNCTION", g(t)),
    "function_calls_function_test":
        lambda t: calls(t, "FUNCTION", "FUNCTION", g(t), function_name=t["functionName"]),
    "program_calls_class_function_test":
        lambda t: calls(t, "PROGRAM", "CLASS_FUNCTION", g(t)),
    "class_function_calls_function_test":
        lambda t: calls(t, "CLASS", "FUNCTION", g(t),
                        class_name=t["className"], function_name=t["classFunctionName"]),
    "program_calls_print_test":
        lambda t: calls(t, "PROGRAM", "FUNCTION",
                        synth(t["programCallsPrint"], ["print"],
                              not t["programCallsPrint"].get("mustNotCall", False))),
    "function_calls_print_test":
        lambda t: calls(t, "FUNCTION", "FUNCTION",
                        synth(t["callsCheck"], ["print"],
                              not t["callsCheck"].get("mustNotCall", False)),
                        function_name=t["functionName"]),

    # --- definitions ------------------------------------------------------------------------
    "program_defines_function_test":
        lambda t: defines(t, "PROGRAM", "FUNCTION", g(t)),
    "class_defines_function_test":
        lambda t: defines(t, "CLASS", "FUNCTION", g(t), class_name=t["className"]),
    "program_defines_class_test":
        lambda t: defines(t, "PROGRAM", "CLASS", g(t)),

    # --- contains ------------------------------------------------------------------------------
    "program_contains_keyword_test":
        lambda t: contains(t, "PROGRAM", to_long(g(t))),
    "function_contains_keyword_test":
        lambda t: contains(t, "FUNCTION", to_long(g(t)), function_name=t["functionName"]),
    "program_imports_module_test":
        lambda t: contains(t, "PROGRAM", g(t), what="KEYWORD_WITH_PRECEDING_ARG", arg="import"),
    "program_contains_loop_test":
        lambda t: contains(t, "PROGRAM",
                           synth(t["programContainsLoop"], ["for", "while"],
                                 not t["programContainsLoop"].get("mustNotContain", False))),
    "function_contains_loop_test":
        lambda t: contains(t, "FUNCTION",
                           synth(t["containsLoop"], ["for", "while"],
                                 not t["containsLoop"].get("mustNotContain", False)),
                           function_name=t["functionName"]),
    "program_contains_try_except_test":
        lambda t: contains(t, "PROGRAM",
                           synth(t["programContainsTryExcept"], ["try", "except"],
                                 not t["programContainsTryExcept"].get("mustNotContain", False))),
    "function_contains_return_test":
        lambda t: contains(t, "FUNCTION",
                           synth(t["containsReturn"], ["return"],
                                 not t["containsReturn"].get("mustNotContain", False)),
                           function_name=t["functionName"]),

    # --- function properties --------------------------------------------------------------------
    "function_is_recursive_test":
        lambda t: prop(t, "RECURSIVE", t["isRecursive"], "mustNotBeRecursive"),
    "function_is_pure_test":
        lambda t: prop(t, "PURE", t["containsLocalVars"], "mustNotContain"),
}

UNCHANGED = {
    "program_execution_test", "function_execution_test", "class_instance_test", "placeholder_test",
    "contains_test", "calls_test", "definition_test", "function_is_test",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--export", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    args = ap.parse_args()

    rows = [json.loads(l) for l in args.export.read_text(encoding="utf-8").splitlines() if l.strip()]
    ex_dir = args.out / "exercises"
    if ex_dir.exists():
        shutil.rmtree(ex_dir)
    ex_dir.mkdir(parents=True)

    converted = Counter()
    changed = unchanged = skipped = 0
    unknown, contradictions = Counter(), []

    for row in rows:
        eid = int(row["exercise_id"])
        d = ex_dir / str(eid)
        d.mkdir()
        assets = {a["file_name"]: a["file_content"] for a in row["assets"]}

        for name, content in assets.items():
            if name != "tsl.json":
                (d / name).write_text(content, encoding="utf-8")
        meta = {k: v for k, v in row.items() if k != "assets"}
        (d / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

        if "tsl.json" not in assets:
            skipped += 1
            continue
        spec = load_spec(assets["tsl.json"])
        if spec is None:
            skipped += 1
            continue

        tests, touched = [], False
        for t in spec.get("tests", []):
            ty = t.get("type")
            if ty in UNCHANGED:
                tests.append(t)
                continue
            fn = MIGRATIONS.get(ty)
            if fn is None:
                unknown[ty] += 1
                tests.append(t)
                continue
            new = fn(t)
            converted[ty] += 1
            touched = True
            # The old messages describe a check the polarity says is inverted. Preserved as-is,
            # because "fix" here means silently changing what a teacher's exercise asserts.
            if ty == "function_is_pure_test" and not new["propertyCheck"]["mustHaveProperty"]:
                contradictions.append((eid, ty))
            tests.append(new)

        spec["tests"] = tests
        (d / "tsl.json").write_text(json.dumps(spec, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
        if touched:
            changed += 1
        else:
            unchanged += 1

    print(f"exercises              {len(rows)}")
    print(f"  rewritten            {changed}")
    print(f"  already fine         {unchanged}")
    print(f"  skipped (no spec)    {skipped}")
    print(f"\ntests converted        {sum(converted.values())}")
    for t, n in converted.most_common():
        print(f"  {t:<40} {n}")
    if unknown:
        print("\nNOT MIGRATED — no mapping, left untouched:")
        for t, n in unknown.most_common():
            print(f"  {t:<40} {n}")
    if contradictions:
        print(f"\nreview by hand ({len(contradictions)}): messages read as the opposite of the check")
        for eid, ty in contradictions:
            print(f"  exercise {eid}  {ty}")
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
