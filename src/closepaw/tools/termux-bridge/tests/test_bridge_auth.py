"""B2 (review 6555767212): the bridge must not execute commands for unauthenticated callers.

Every test here is written against the *delivered* bridge: a local process that can reach
127.0.0.1:18422 but has not been given the Termux-private token must never reach
subprocess.Popen, and the daemon must refuse to start at all when no token exists.
"""

import os
import subprocess
import sys

import pytest

from conftest import (
    BRIDGE_SCRIPT,
    HOST,
    TOKEN,
    start_process,
    stop_process,
    write_token,
)


def token_file(home):
    return home / ".closepaw" / "token"


def test_exec_without_authorization_is_rejected_and_runs_nothing(bridge_server, tmp_path):
    marker = tmp_path / "unauthorized-ran"
    command = f"echo pwned > {marker}"

    response = bridge_server.post_json("/v1/exec", {"command": command}, token=None)

    assert response.status == 401
    assert response.body["error"] == "unauthorized"
    assert not marker.exists()


def test_exec_with_wrong_token_is_rejected_and_runs_nothing(bridge_server, tmp_path):
    marker = tmp_path / "wrong-token-ran"
    command = f"echo pwned > {marker}"

    response = bridge_server.post_json(
        "/v1/exec", {"command": command}, token=TOKEN + "-not-the-token"
    )

    assert response.status == 401
    assert response.body["error"] == "unauthorized"
    assert not marker.exists()


def test_health_without_authorization_is_rejected(bridge_server):
    response = bridge_server.get_json("/v1/health", token=None)

    assert response.status == 401
    assert response.body["error"] == "unauthorized"


def test_exec_with_the_stored_token_still_runs(bridge_server):
    response = bridge_server.post_json("/v1/exec", {"command": "echo hi"})

    assert response.status == 200
    assert response.body["stdout"] == "hi\n"
    assert response.body["exit_code"] == 0


def test_bridge_reports_version_so_a_stale_deploy_is_detected(bridge_server):
    response = bridge_server.get_json("/v1/health")

    assert response.status == 200
    assert response.body["version"] == "2"


def test_bridge_refuses_to_start_when_no_token_file_exists(free_port, tmp_path):
    proc = start_process(free_port, tmp_path, token=None)
    try:
        proc.wait(timeout=10)
    finally:
        stop_process(proc)

    _, stderr = proc.communicate(timeout=5)
    assert proc.returncode != 0
    assert "token_missing" in stderr


def test_bridge_refuses_to_start_when_the_token_is_too_short(free_port, tmp_path):
    write_token(tmp_path, "short")
    proc = start_process(free_port, tmp_path, token=None)
    try:
        proc.wait(timeout=10)
    finally:
        stop_process(proc)

    _, stderr = proc.communicate(timeout=5)
    assert proc.returncode != 0
    assert "token_missing" in stderr


def test_bridge_refuses_a_non_loopback_bind_address(free_port, tmp_path):
    write_token(tmp_path)
    env = os.environ.copy()
    env["HOME"] = str(tmp_path)
    proc = subprocess.Popen(
        [
            sys.executable,
            str(BRIDGE_SCRIPT),
            "--host",
            "0.0.0.0",
            "--port",
            str(free_port),
            "--idle-timeout-sec",
            "0",
        ],
        cwd=str(tmp_path),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        proc.wait(timeout=10)
    finally:
        stop_process(proc)

    _, stderr = proc.communicate(timeout=5)
    assert proc.returncode != 0
    assert "host_not_allowed" in stderr


def test_token_file_is_not_world_readable(bridge_server):
    path = token_file(bridge_server.home)

    assert path.exists()
    if os.name == "nt":
        pytest.skip("POSIX file modes are not observable on the Windows test host")
    mode = path.stat().st_mode & 0o777
    assert mode & 0o077 == 0, f"token file mode {oct(mode)} is readable by others"
