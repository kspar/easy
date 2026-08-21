#!/usr/bin/env python3
"""Turn the .adoc fixtures into an export JSONL that dry_run.py accepts.

`text_html` is produced by rendering each fixture with the SAME pinned asciidoctor image the
converter uses, which is what makes the comparison meaningful: production's stored HTML also came
from Asciidoctor, so a clean conversion should round-trip to the same visible text.

The caveat, stated so nobody trusts this further than it goes: production's HTML came from core's
old AsciidoctorService, not from the CLI, so this proves the *toolchain* rather than fidelity to
production's exact bytes. Only a real export does the latter.

    python3 build-corpus.py > corpus.jsonl
"""
import json, pathlib, re, subprocess, sys

# Kept in step with dry_run.py deliberately — if that pins a new digest, this must too, or the
# fixture stops testing what the converter actually runs.
ASCIIDOCTOR = ("asciidoctor/docker-asciidoctor@sha256:"
               "f90b2f0c1497bb0a4c4aa7d571de27becb8cdc2592b2b2aa851fcb8ac1e01f95")

# `EasyCodeProcessor` from core's old adoc_service.kt (c4550ede, the commit production runs). It is
# an Asciidoctor *postprocessor* — a regex over the rendered HTML, not an attribute substitution —
# which is why setting `-a run=...` alone does not reproduce it. Note `in` maps to class `input`.
EASY_CODE = re.compile(r"\$(run|in|nohl)\[(.+?)(?<!\\)\]", re.S)
EASY_CLASS = {"run": "codehl run", "in": "codehl input", "nohl": "codehl nohl"}


def easy_code_postprocess(html: str) -> str:
    return EASY_CODE.sub(
        lambda m: f'<span class="{EASY_CLASS[m.group(1)]}">{m.group(2).replace(chr(92) + "]", "]")}</span>',
        html)


here = pathlib.Path(__file__).parent
adoc_dir = here / "adoc"

rows = []
for n, path in enumerate(sorted(adoc_dir.glob("*.adoc")), start=1):
    src = path.read_text(encoding="utf-8")
    r = subprocess.run(
        ["docker", "run", "--rm", "-i", "--entrypoint", "asciidoctor", ASCIIDOCTOR,
         "-b", "html5", "-s",
         # The same attribute values core's old AsciidoctorService set (adoc_service.kt at
         # c4550ede, the commit production runs). Getting these wrong makes the fixture lie: with
         # `run=` empty, Asciidoctor emits the literal `$run[...]` into the HTML, the converter
         # strips it as it should, and the comparison reports a difference that does not exist in
         # production — where the marker is a codehl span whose text `visible_text` already keeps.
         "-a", 'run=<span class="codehl run">',
         "-a", 'nur=</span>',
         "-a", 'in=<span class="codehl in">',
         "-a", 'ni=</span>',
         "-a", 'nohl=<span class="codehl nohl">',
         "-a", 'lhon=</span>',
         "-o", "-", "-"],
        input=src, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"{path.name}: asciidoctor failed: {r.stderr[:200]}", file=sys.stderr)
        sys.exit(1)
    rows.append({
        "exercise_id": 900000 + n,      # far above any real id, so a mix-up is obvious
        "version_id": 900000 + n,
        "title": path.stem,
        "text_adoc": src,
        "text_html": easy_code_postprocess(r.stdout),
    })

for row in rows:
    print(json.dumps(row, ensure_ascii=False))
