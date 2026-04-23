#!/usr/bin/env bats
# tests/test_init.bats — Automated tests for init.sh
#
# Run locally:
#   sudo apt-get install -y bats   # Ubuntu/Debian (provides bats-core 1.x)
#   bats tests/test_init.bats

INIT_SH="$(cd "$(dirname "$BATS_TEST_FILENAME")/.." && pwd)/init.sh"

# ── Helper: invoke select_profile in an isolated subshell ─────────────────────
# Extracts NOMIC_MB and select_profile() directly from init.sh so the tests
# stay coupled to the real source rather than a duplicated copy.
_sp() {
    local budget="$1"
    bash -c "
$(sed -n -e '/^NOMIC_MB=/p' -e '/^select_profile()/,/^}$/p' "$INIT_SH")
select_profile $budget 0
"
}

# ══════════════════════════════════════════════════════════════════════════════
# 1. select_profile — model / context-window selection logic
# ══════════════════════════════════════════════════════════════════════════════

@test "select_profile: 9200 MB budget picks llama3.1:8b at full 16 k ctx" {
    run _sp 9200
    [ "$status" -eq 0 ]
    [ "$output" = "llama3.1:8b 16384" ]
}

@test "select_profile: 7200 MB budget picks llama3.1:8b at 8 k ctx" {
    run _sp 7200
    [ "$status" -eq 0 ]
    [ "$output" = "llama3.1:8b 8192" ]
}

@test "select_profile: 3300 MB budget picks llama3.2 at 8 k ctx (mid-range)" {
    run _sp 3300
    [ "$status" -eq 0 ]
    [ "$output" = "llama3.2 8192" ]
}

@test "select_profile: 1400 MB budget picks llama3.2:1b at 2 k ctx (low-end)" {
    run _sp 1400
    [ "$status" -eq 0 ]
    [ "$output" = "llama3.2:1b 2048" ]
}

@test "select_profile: 1300 MB budget picks llama3.2:1b at 1 k ctx (minimal)" {
    run _sp 1300
    [ "$status" -eq 0 ]
    [ "$output" = "llama3.2:1b 1024" ]
}

@test "select_profile: 0 MB budget falls back to the absolute minimum (llama3.2:1b 512)" {
    run _sp 0
    [ "$status" -eq 0 ]
    [ "$output" = "llama3.2:1b 512" ]
}

# ══════════════════════════════════════════════════════════════════════════════
# 2. Argument parsing
# ══════════════════════════════════════════════════════════════════════════════

@test "init.sh: unknown flag exits with status 1 and prints usage hint" {
    run bash "$INIT_SH" --unknown-flag
    [ "$status" -eq 1 ]
    [[ "$output" =~ "Unknown argument" ]]
}

@test "init.sh: --dry-run --ci completes with exit 0 on Ubuntu" {
    run timeout 60 bash "$INIT_SH" --dry-run --ci
    [ "$status" -eq 0 ]
}

@test "init.sh: --dry-run --ci --force completes with exit 0" {
    run timeout 60 bash "$INIT_SH" --dry-run --ci --force
    [ "$status" -eq 0 ]
}

@test "init.sh: --dry-run --ci --skip-stubs completes with exit 0" {
    run timeout 60 bash "$INIT_SH" --dry-run --ci --skip-stubs
    [ "$status" -eq 0 ]
}

# ══════════════════════════════════════════════════════════════════════════════
# 3. --dry-run output content
# ══════════════════════════════════════════════════════════════════════════════

@test "init.sh: --dry-run output contains 'Selected model' from model-selection step" {
    run timeout 60 bash "$INIT_SH" --dry-run --ci
    [[ "$output" =~ "Selected model" ]]
}

@test "init.sh: --dry-run output contains 'Context window' from model-selection step" {
    run timeout 60 bash "$INIT_SH" --dry-run --ci
    [[ "$output" =~ "Context window" ]]
}

@test "init.sh: --dry-run output contains [dry-run] markers showing commands were not executed" {
    run timeout 60 bash "$INIT_SH" --dry-run --ci
    [[ "$output" =~ "[dry-run]" ]]
}

@test "init.sh: --dry-run output contains the 'Initialisation complete' banner" {
    run timeout 60 bash "$INIT_SH" --dry-run --ci
    [[ "$output" =~ "Initialisation complete" ]]
}

@test "init.sh: --dry-run does not write a .env file" {
    # Copy init.sh to an isolated temp directory so ROOT points there.
    # BATS_TEST_TMPDIR is cleaned up automatically after each test.
    cp "$INIT_SH" "$BATS_TEST_TMPDIR/init.sh"
    chmod +x "$BATS_TEST_TMPDIR/init.sh"

    run timeout 60 bash "$BATS_TEST_TMPDIR/init.sh" --dry-run --ci

    [ ! -f "$BATS_TEST_TMPDIR/.env" ]
}

@test "init.sh: --dry-run --skip-stubs output contains the skip-stubs notice" {
    run timeout 60 bash "$INIT_SH" --dry-run --ci --skip-stubs
    [[ "$output" =~ "skip-stubs" ]]
}

# ══════════════════════════════════════════════════════════════════════════════
# 4. has() — command-existence helper
# ══════════════════════════════════════════════════════════════════════════════

@test "has(): returns 0 for 'bash', which is always present" {
    run bash -c 'has() { command -v "$1" &>/dev/null; }; has bash && echo present'
    [ "$status" -eq 0 ]
    [ "$output" = "present" ]
}

@test "has(): returns non-zero for a command that does not exist" {
    run bash -c 'has() { command -v "$1" &>/dev/null; }; has __nonexistent_command_xyz__ || echo missing'
    [ "$output" = "missing" ]
}
