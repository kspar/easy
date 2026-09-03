"""The rehearsal config: production's application.yaml with everything dangerous pointed at nowhere.

Every test here is about a way the rehearsal could reach a real system. The transform must remove
each, and — separately — `problems()` must notice each if it were ever put back. The second half is
the one that matters: a guard that has never seen the thing it guards against is a guard that may
not work.
"""

from __future__ import annotations

import copy
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "files"))

import easy_rehearsal_config as rc  # noqa: E402

# What roles/core_config renders for a production host, abridged to the keys that matter. Names
# are example.org's: this file is public, and the real ones live in a gitignored inventory.
PROD = {
    "server": {"address": "127.0.0.1", "port": 8080},
    "spring": {
        "config": {"import": "file:/srv/easy/conf/secrets.yaml"},
        "datasource": {"jdbc-url": "jdbc:postgresql://127.0.0.1:5432/easyems", "username": "easyems",
                       "hikari": {"maximum-pool-size": 20, "minimum-idle": 5}},
        "mail": {"host": "smtp.example.org", "port": 587, "properties": {"mail": {"smtp": {"auth": True}}}},
        "security": {"oauth2": {"resourceserver": {"jwt": {
            "jwk-set-uri": "https://idp.example.org/auth/realms/master/protocol/openid-connect/certs",
            "issuer-uri": "https://idp.example.org/auth/realms/master"}}}},
    },
    "easy": {
        "core": {
            "auth-enabled": True,
            "cors": {"allowed-origins": "https://app.example.org"},
            "mail": {"from": "noreply@example.org", "sys": {"enabled": True, "to": "ops@example.org"}, "user": {"enabled": True}},
            "auto-assess": {"fixed-delay": {"ms": "2000"}, "fixed-delay-observer-clear": {"ms": "60000"}},
            "statistics": {"fixed-delay": {"ms": "60000"}},
            "moodle-sync": {"users": {"url": "https://moodle.example.org/webservice/rest/server.php", "cron": "0 0 4 * * ?"},
                            "grades": {"url": "https://moodle.example.org/webservice/rest/server.php"},
                            "course-allowlist": ""},
            "storage": {"backend": "s3", "local": {"dir": "/srv/easy/files"},
                        "s3": {"bucket": "easy-prod", "region": "eu-north-1"}},
            "stored-file-sweep": {"cron": "0 0 3 * * ?", "grace-hours": 24, "delete": True},
            "exercise-index-normalisation": {"cron": "0 30 3 * * ?"},
            "keycloak": {"cron": "0 0 2 * * ?", "base-url": "https://idp.example.org", "realm": "master"},
            "youtrack": {"enabled": True, "base-url": "https://tracker.example.org", "retry-cron": "0 */10 * * * ?"},
        },
        "web": {"base-url": "https://app.example.org"},
    },
}
SECRETS = ["spring.datasource.password", "easy.core.keycloak.client-secret", "easy.core.moodle-sync.wstoken",
           "easy.core.youtrack.token", "easy.core.storage.s3.access-key", "easy.core.storage.s3.secret-key"]
STORAGE = "/var/lib/easy-rollout-db/work/rehearsal/files"


def rehearsal(port=8091, **kw):
    return rc.transform(PROD, port, "pw-123", SECRETS, STORAGE, **kw)


def test_transform_produces_a_config_the_guard_accepts():
    assert rc.problems(rehearsal(), 8091) == []


def test_never_cron_is_six_fields_because_spring_refuses_seven():
    assert len(rc.NEVER_CRON.split()) == 6


def test_production_config_itself_is_refused_loudly():
    problems = rc.problems(PROD, 8091)
    # Not one complaint but the whole list — the reader should see everything that is wrong.
    assert len(problems) >= 12
    joined = " ".join(problems)
    for word in ("Moodle", "mail", "storage", "schedule", "YouTrack", "secrets", "IdP", "poller"):
        assert word in joined


def test_database_is_the_scratch_one_and_the_real_secrets_are_not_imported():
    cfg = rehearsal(db_host="127.0.0.1", db_port=5433)
    assert cfg["spring"]["datasource"]["jdbc-url"] == "jdbc:postgresql://127.0.0.1:5433/easyems_rehearsal"
    assert cfg["spring"]["datasource"]["username"] == "easyems_rehearsal"
    assert cfg["spring"]["datasource"]["password"] == "pw-123"
    assert "import" not in cfg["spring"]["config"]


def test_other_secrets_exist_by_name_and_are_worthless_by_value():
    cfg = rehearsal()
    assert cfg["easy"]["core"]["keycloak"]["client-secret"] == rc.DUMMY
    assert cfg["easy"]["core"]["moodle-sync"]["wstoken"] == rc.DUMMY
    assert cfg["easy"]["core"]["youtrack"]["token"] == rc.DUMMY
    # The s3 block is gone entirely, so its keys must not have been recreated under it.
    assert "s3" not in cfg["easy"]["core"]["storage"]


def test_every_cron_anywhere_is_pinned_to_never():
    cfg = rehearsal()
    crons = [(p, v) for p, v in rc._walk(cfg) if p.endswith("cron")]
    assert len(crons) == 5, crons
    assert all(v == rc.NEVER_CRON for _, v in crons)


def test_every_fixed_delay_poller_is_stalled_including_the_executor_sync():
    cfg = rehearsal()
    for key in rc.FIXED_DELAY_KEYS:
        assert rc._get(cfg, key) == rc.STALLED_MS, key
    assert "executor-sync" in " ".join(rc.FIXED_DELAY_KEYS)


def test_moodle_cannot_be_reached_and_the_allowlist_is_not_empty():
    cfg = rehearsal()["easy"]["core"]["moodle-sync"]
    assert cfg["users"]["url"].startswith("http://127.0.0.1:9/")
    assert cfg["grades"]["url"].startswith("http://127.0.0.1:9/")
    # Empty means unrestricted in core, which is why the transform names a course that does not exist.
    assert cfg["course-allowlist"] == rc.NO_COURSE


def test_idp_is_pointed_at_nowhere_so_no_token_is_ever_accepted():
    cfg = rehearsal()
    for key in rc.JWT_KEYS:
        assert rc._get(cfg, key).startswith(rc.DISCARD), key
    assert cfg["easy"]["core"]["keycloak"]["base-url"] == "http://127.0.0.1:9"


def test_mail_link_base_is_the_key_core_reads():
    cfg = rehearsal()
    assert cfg["easy"]["web"]["base-url"] == rc.DISCARD
    assert "web" not in cfg["easy"]["core"], "easy.core.web.base-url is a key nothing reads"


@pytest.mark.parametrize("path,value,word", [
    ("server.address", "0.0.0.0", "loopback"),
    ("server.port", 8080, "port"),
    ("spring.datasource.jdbc-url", "jdbc:postgresql://127.0.0.1:5432/easyems", "database"),
    ("spring.config.import", "file:/srv/easy/conf/secrets.yaml", "secrets"),
    ("spring.mail.host", "smtp.example.org", "mail"),
    ("spring.mail.properties.mail.smtp.auth", True, "mail auth"),
    ("easy.core.mail.sys.enabled", True, "system mail"),
    ("easy.core.mail.user.enabled", True, "user mail"),
    ("easy.core.moodle-sync.grades.url", "https://moodle.example.org/ws", "Moodle"),
    ("easy.core.moodle-sync.course-allowlist", "", "UNRESTRICTED"),
    ("easy.core.storage.backend", "s3", "storage"),
    ("easy.core.storage.local.dir", "/srv/easy/files", "scratch"),
    ("easy.core.stored-file-sweep.delete", True, "sweep"),
    ("easy.core.keycloak.cron", "0 0 2 * * ?", "schedule"),
    ("easy.core.youtrack.retry-cron", "0 */10 * * * ?", "schedule"),
    ("easy.core.auto-assess.fixed-delay.ms", "2000", "poller"),
    ("easy.core.auto-assess.executor-sync.fixed-delay.ms", "60000", "poller"),
    ("easy.core.statistics.fixed-delay.ms", "60000", "poller"),
    ("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "https://idp.example.org/auth/realms/master/protocol/openid-connect/certs", "IdP"),
    ("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://idp.example.org/auth/realms/master", "IdP"),
    ("easy.core.keycloak.base-url", "https://idp.example.org", "IdP"),
    ("easy.web.base-url", "https://app.example.org", "link"),
    ("easy.core.youtrack.enabled", True, "YouTrack"),
    ("easy.core.auth-enabled", False, "auth"),
])
def test_guard_notices_each_way_back_to_a_real_system(path, value, word):
    cfg = rehearsal()
    rc._set(cfg, path, value)
    problems = rc.problems(cfg, 8091)
    assert problems, f"putting back {path}={value!r} was not noticed"
    assert any(word in p for p in problems), problems


def test_guard_notices_a_reintroduced_s3_block():
    cfg = rehearsal()
    cfg["easy"]["core"]["storage"]["s3"] = {"bucket": "easy-prod"}
    assert any("s3" in p for p in rc.problems(cfg, 8091))


def test_transform_does_not_mutate_its_input():
    before = copy.deepcopy(PROD)
    rehearsal()
    assert PROD == before


def test_secret_key_paths_are_names_only():
    paths = rc.secret_key_paths({"spring": {"datasource": {"password": "hunter2"}},
                                 "easy": {"core": {"keycloak": {"client-secret": "s3cret"}}}})
    assert sorted(paths) == ["easy.core.keycloak.client-secret", "spring.datasource.password"]
    assert "hunter2" not in " ".join(paths)


def test_main_refuses_to_write_a_config_the_guard_rejects(tmp_path, monkeypatch):
    yaml = pytest.importorskip("yaml")
    app = tmp_path / "application.yaml"
    app.write_text(yaml.safe_dump(PROD))
    sec = tmp_path / "secrets.yaml"
    sec.write_text("spring:\n  datasource:\n    password: hunter2\n")
    pwf = tmp_path / "pw"
    pwf.write_text("pw-123\n")
    out = tmp_path / "out.json"
    argv = ["--application-yaml", str(app), "--secrets-yaml", str(sec), "--port", "8091",
            "--db-password-file", str(pwf), "--storage-dir", STORAGE, "--out", str(out)]
    assert rc.main(argv) == 0
    written = out.read_text()
    assert "hunter2" not in written and "pw-123" in written
    assert rc.problems(__import__("json").loads(written), 8091) == []
    # And the negative case: a storage dir that is not a scratch one is refused before writing.
    out.unlink()
    bad = argv[:]
    bad[bad.index("--storage-dir") + 1] = "/srv/easy/files"
    assert rc.main(bad) == 1
    assert not out.exists()
