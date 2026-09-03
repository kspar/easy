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

# What roles/core_config renders for a production host, abridged to the keys that matter.
PROD = {
    "server": {"address": "127.0.0.1", "port": 8080},
    "spring": {
        "config": {"import": "file:/srv/easy/conf/secrets.yaml"},
        "datasource": {"jdbc-url": "jdbc:postgresql://127.0.0.1:5432/easyems", "username": "easyems",
                       "hikari": {"maximum-pool-size": 20, "minimum-idle": 5}},
        "mail": {"host": "smtp.ut.ee", "port": 587, "properties": {"mail": {"smtp": {"auth": True}}}},
        "security": {"oauth2": {"resourceserver": {"jwt": {"issuer-uri": "https://idp.example/auth/realms/master"}}}},
    },
    "easy": {
        "core": {
            "auth-enabled": True,
            "cors": {"allowed-origins": "https://lahendus.ut.ee"},
            "mail": {"from": "noreply@ut.ee", "sys": {"enabled": True, "to": "ops@ut.ee"}, "user": {"enabled": True}},
            "auto-assess": {"fixed-delay": {"ms": "2000"}, "fixed-delay-observer-clear": {"ms": "60000"}},
            "statistics": {"fixed-delay": {"ms": "60000"}},
            "moodle-sync": {"users": {"url": "https://moodle.ut.ee/webservice/rest/server.php", "cron": "0 0 4 * * ?"},
                            "grades": {"url": "https://moodle.ut.ee/webservice/rest/server.php"},
                            "course-allowlist": ""},
            "storage": {"backend": "s3", "local": {"dir": "/srv/easy/files"},
                        "s3": {"bucket": "easy-prod", "region": "eu-north-1"}},
            "stored-file-sweep": {"cron": "0 0 3 * * ?", "grace-hours": 24, "delete": True},
            "exercise-index-normalisation": {"cron": "0 30 3 * * ?"},
            "keycloak": {"cron": "0 0 2 * * ?", "base-url": "https://idp.example", "realm": "master"},
            "youtrack": {"enabled": True, "base-url": "https://easy.youtrack.cloud", "retry-cron": "0 */10 * * * ?"},
        },
        "web": {"base-url": "https://lahendus.ut.ee"},
    },
}
SECRETS = ["spring.datasource.password", "easy.core.keycloak.client-secret", "easy.core.moodle-sync.wstoken",
           "easy.core.youtrack.token", "easy.core.storage.s3.access-key", "easy.core.storage.s3.secret-key"]


def rehearsal(port=8091):
    return rc.transform(PROD, port, "pw-123", SECRETS, "/srv/easy/rollout/rehearsal/files")


def test_transform_produces_a_config_the_guard_accepts():
    assert rc.problems(rehearsal(), 8091) == []


def test_production_config_itself_is_refused_loudly():
    problems = rc.problems(PROD, 8091)
    # Not one complaint but the whole list — the reader should see everything that is wrong.
    assert len(problems) >= 10
    joined = " ".join(problems)
    for word in ("Moodle", "mail", "storage", "schedule", "YouTrack", "secrets"):
        assert word in joined


def test_database_is_the_scratch_one_and_the_real_secrets_are_not_imported():
    cfg = rehearsal()
    assert cfg["spring"]["datasource"]["jdbc-url"].endswith("/easyems_rehearsal")
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


def test_moodle_cannot_be_reached_and_the_allowlist_is_not_empty():
    cfg = rehearsal()["easy"]["core"]["moodle-sync"]
    assert cfg["users"]["url"].startswith("http://127.0.0.1:9/")
    assert cfg["grades"]["url"].startswith("http://127.0.0.1:9/")
    # Empty means unrestricted in core, which is why the transform names a course that does not exist.
    assert cfg["course-allowlist"] == rc.NO_COURSE


@pytest.mark.parametrize("path,value,word", [
    ("server.address", "0.0.0.0", "loopback"),
    ("server.port", 8080, "port"),
    ("spring.datasource.jdbc-url", "jdbc:postgresql://127.0.0.1:5432/easyems", "database"),
    ("spring.config.import", "file:/srv/easy/conf/secrets.yaml", "secrets"),
    ("spring.mail.host", "smtp.ut.ee", "mail"),
    ("easy.core.mail.sys.enabled", True, "system mail"),
    ("easy.core.mail.user.enabled", True, "user mail"),
    ("easy.core.moodle-sync.grades.url", "https://moodle.ut.ee/ws", "Moodle"),
    ("easy.core.moodle-sync.course-allowlist", "", "UNRESTRICTED"),
    ("easy.core.storage.backend", "s3", "storage"),
    ("easy.core.stored-file-sweep.delete", True, "sweep"),
    ("easy.core.keycloak.cron", "0 0 2 * * ?", "schedule"),
    ("easy.core.youtrack.retry-cron", "0 */10 * * * ?", "schedule"),
    ("easy.core.auto-assess.fixed-delay.ms", "2000", "auto-assessment"),
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
