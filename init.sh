#!/usr/bin/env bash
# Re-exec under Bash when invoked with sh.
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi

# init.sh — One-shot environment bootstrap for the Agentic RAG System.
#
# Detects hardware, installs GPU drivers, selects an Ollama model and context
# window that fits entirely in VRAM (or RAM), installs all project dependencies,
# and writes a ready-to-use .env file.
#
# Requirements : Ubuntu Linux (20.04 / 22.04 / 24.04)
# Usage        : ./init.sh [--ci] [--dry-run] [--force] [--skip-stubs]
#
#   --ci          Non-interactive mode; suppress prompts, use all defaults.
#   --dry-run     Print every command that would run without executing it.
#   --force       Re-run steps even when the artefact already exists
#                 (re-creates venv, re-runs npm install, overwrites .env).
#   --skip-stubs  Skip gRPC stub generation (useful after first run).

set -euo pipefail

# ── Colours (match start.sh style) ───────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# ── Argument Parsing ──────────────────────────────────────────────────────────
CI=0
DRY_RUN=0
FORCE=0
SKIP_STUBS=0

for arg in "$@"; do
    case "$arg" in
        --ci)          CI=1 ;;
        --dry-run)     DRY_RUN=1 ;;
        --force)       FORCE=1 ;;
        --skip-stubs)  SKIP_STUBS=1 ;;
        *)
            echo -e "${RED}Unknown argument: $arg${NC}"
            echo "Usage: ./init.sh [--ci] [--dry-run] [--force] [--skip-stubs]"
            exit 1
            ;;
    esac
done

# ── State flags set during the run ───────────────────────────────────────────
NEEDS_REBOOT=0
APT_UPDATED=0

# ── Root of the repository ────────────────────────────────────────────────────
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Helper functions ──────────────────────────────────────────────────────────
log_info()    { echo -e "${GREEN}  ✓  $*${NC}"; }
log_warn()    { echo -e "${YELLOW}  !  $*${NC}"; }
log_error()   { echo -e "${RED}  ✗  $*${NC}"; }
log_section() { echo -e "\n${BOLD}${CYAN}▶ $*${NC}"; }

# Execute or dry-run a command.
run() {
    if [[ $DRY_RUN -eq 1 ]]; then
        echo -e "${YELLOW}[dry-run]${NC} $*"
    else
        "$@"
    fi
}

# Execute a shell pipeline or redirection-aware command.
run_shell() {
    local command=$1
    if [[ $DRY_RUN -eq 1 ]]; then
        echo -e "${YELLOW}[dry-run]${NC} ${command}"
    else
        bash -lc "$command"
    fi
}

# Run apt-get update at most once per invocation.
apt_update_once() {
    if [[ $APT_UPDATED -eq 0 ]]; then
        log_info "Refreshing package lists…"
        run sudo apt-get update -qq
        APT_UPDATED=1
    fi
}

# Return 0 if a command is available.
has() { command -v "$1" &>/dev/null; }

# Return 0 if a command name or absolute path is executable.
has_exec() {
    local target=$1
    if [[ "$target" == */* ]]; then
        [[ -x "$target" ]]
    else
        has "$target"
    fi
}

# Install apt packages only when missing.
apt_install_if_missing() {
    local missing=()
    local pkg
    for pkg in "$@"; do
        if ! dpkg -s "$pkg" &>/dev/null; then
            missing+=("$pkg")
        fi
    done

    if [[ ${#missing[@]} -gt 0 ]]; then
        apt_update_once
        run sudo apt-get install -y -qq "${missing[@]}"
    fi
}

java_version() {
    if has java; then
        java -version 2>&1 | grep -oP '(?<=version ")[0-9]+' | head -1
    else
        echo 0
    fi
}

javac_version() {
    if has javac; then
        javac -version 2>&1 | grep -oP '[0-9]+' | head -1
    else
        echo 0
    fi
}

python_version() {
    if has python3; then
        python3 --version 2>&1 | grep -oP '3\.\K[0-9]+' | head -1
    else
        echo 0
    fi
}

node_version() {
    if has node; then
        node --version 2>&1 | grep -oP 'v\K[0-9]+' | head -1
    else
        echo 0
    fi
}

find_java_home() {
    local candidate
    local major

    if has javac && [[ $(javac_version) -ge 21 ]]; then
        dirname "$(dirname "$(readlink -f "$(command -v javac)")")"
        return 0
    fi

    if has java && [[ $(java_version) -ge 21 ]]; then
        dirname "$(dirname "$(readlink -f "$(command -v java)")")"
        return 0
    fi

    for candidate in /usr/lib/jvm/*; do
        if [[ -x "$candidate/bin/javac" ]]; then
            major=$("$candidate/bin/javac" -version 2>&1 | grep -oP '[0-9]+' | head -1 || echo 0)
            if [[ ${major:-0} -ge 21 ]]; then
                echo "$candidate"
                return 0
            fi
        fi
    done

    return 1
}

describe_command() {
    local target=$1
    shift

    if has_exec "$target"; then
        "$target" "$@" 2>&1 | head -1
    else
        echo "not installed"
    fi
}

# Print a two-column summary row.
summary_row() { printf "  %-30s %s\n" "$1" "$2"; }

# ── Header ────────────────────────────────────────────────────────────────────
echo -e "${BOLD}"
echo "  ╔═════════════════════════════════════════════════════╗"
echo "  ║   Agentic RAG System — Environment Initialisation   ║"
echo "  ╚═════════════════════════════════════════════════════╝"
echo -e "${NC}"
[[ $DRY_RUN -eq 1 ]]  && echo -e "${YELLOW}  DRY-RUN mode — no changes will be made${NC}\n"
[[ $CI -eq 1 ]]       && echo -e "${CYAN}  CI mode — running non-interactively${NC}\n"

# ── 1. Preflight: Ubuntu ──────────────────────────────────────────────────────
log_section "1/12  Preflight checks"

if [[ ! -f /etc/os-release ]]; then
    log_error "Cannot detect OS. This script requires Ubuntu Linux."
    exit 1
fi
# shellcheck source=/dev/null
source /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
    log_error "Detected OS: ${PRETTY_NAME:-unknown}. This script requires Ubuntu."
    exit 1
fi
log_info "OS: ${PRETTY_NAME}"

# ── 1b. sudo availability ──────────────────────────────────────────────────────
if [[ $DRY_RUN -eq 1 ]]; then
    if has sudo; then
        log_info "sudo: dry-run mode — skipping credential check"
    else
        log_warn "sudo is not installed, but dry-run mode will continue without executing privileged commands."
    fi
else
    if ! has sudo; then
        log_error "sudo is not installed. Please install sudo and re-run."
        exit 1
    fi
    if ! sudo -n true 2>/dev/null; then
        if [[ $CI -eq 1 ]]; then
            log_error "sudo requires a password but --ci was passed. Configure passwordless sudo."
            exit 1
        fi
        echo -e "${YELLOW}  This script requires sudo for some steps. You may be prompted.${NC}"
        sudo -v   # cache credentials up front
    fi
    log_info "sudo: OK"
fi

# Keep sudo alive for the duration of the script.
if [[ $DRY_RUN -eq 0 ]]; then
    ( while true; do sudo -n true; sleep 55; done ) &
    SUDO_KEEPALIVE_PID=$!
    trap 'kill "$SUDO_KEEPALIVE_PID" 2>/dev/null; true' EXIT
fi

# ── 2. Hardware detection ─────────────────────────────────────────────────────
log_section "2/12  Hardware detection"

if ! has lspci; then
    log_info "Installing pciutils for hardware detection…"
    apt_install_if_missing pciutils
fi

GPU_VENDOR="none"     # none | nvidia | amd
VRAM_MB=0
RAM_MB=$(free -m | awk '/Mem:/{print $2}')

# Detect GPU vendor from PCI bus.
if lspci 2>/dev/null | grep -iE 'nvidia' | grep -qiE 'VGA|3D|Display'; then
    GPU_VENDOR="nvidia"
elif lspci 2>/dev/null | grep -iE 'amd.*radeon|radeon|advanced micro.*vga' | grep -qiE 'VGA|3D|Display'; then
    GPU_VENDOR="amd"
fi

log_info "RAM: ${RAM_MB} MB"
log_info "GPU vendor: ${GPU_VENDOR}"

# ── 3. GPU Driver Installation ────────────────────────────────────────────────
log_section "3/12  GPU drivers"

if [[ "$GPU_VENDOR" == "nvidia" ]]; then
    if has nvidia-smi && nvidia-smi &>/dev/null; then
        VRAM_MB=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>/dev/null \
                  | head -1 | tr -d ' ' || echo "0")
        log_info "NVIDIA drivers already loaded — VRAM: ${VRAM_MB} MB"
    else
        log_info "NVIDIA GPU detected; installing drivers via ubuntu-drivers…"
        apt_update_once
        run sudo apt-get install -y -qq ubuntu-drivers-common
        if [[ $CI -eq 1 ]]; then
            run sudo ubuntu-drivers install --gpgpu
        else
            run sudo ubuntu-drivers autoinstall
        fi
        # CUDA toolkit for Ollama GPU acceleration
        if ! dpkg -l | grep -q 'nvidia-cuda-toolkit'; then
            run sudo apt-get install -y -qq nvidia-cuda-toolkit 2>/dev/null || \
                log_warn "nvidia-cuda-toolkit not available in current repo; skipping."
        fi
        # Try nvidia-smi now (works on some systems without reboot).
        if has nvidia-smi && nvidia-smi &>/dev/null; then
            VRAM_MB=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>/dev/null \
                      | head -1 | tr -d ' ' || echo "0")
            log_info "NVIDIA drivers active — VRAM: ${VRAM_MB} MB"
        else
            # Estimate VRAM from PCI memory bar (prefetchable = framebuffer/VRAM).
            PCI_VRAM_RAW=$(lspci -v 2>/dev/null \
                | grep -A 20 -i 'nvidia' \
                | grep 'Memory at.*prefetchable' \
                | grep -oP 'size=\K[0-9]+[KMG]' \
                | sort -h | tail -1 || echo "")
            if [[ -n "$PCI_VRAM_RAW" ]]; then
                UNIT="${PCI_VRAM_RAW: -1}"
                VAL="${PCI_VRAM_RAW%?}"
                case "$UNIT" in
                    G) VRAM_MB=$(( VAL * 1024 )) ;;
                    M) VRAM_MB=$VAL ;;
                    K) VRAM_MB=$(( VAL / 1024 )) ;;
                esac
                log_warn "Drivers installed — reboot required for full activation."
                log_warn "Estimated VRAM from PCI info: ${VRAM_MB} MB"
            else
                log_warn "VRAM could not be determined — will use RAM-based profile."
            fi
            NEEDS_REBOOT=1
        fi
    fi

elif [[ "$GPU_VENDOR" == "amd" ]]; then
    if has rocm-smi && rocm-smi &>/dev/null; then
        # rocm-smi reports VRAM in bytes; convert to MB.
        VRAM_BYTES=$(rocm-smi --showmeminfo vram 2>/dev/null \
                     | grep -i 'Total Memory' | awk '{print $NF}' | head -1 || echo "0")
        VRAM_MB=$(( VRAM_BYTES / 1024 / 1024 ))
        log_info "ROCm already loaded — VRAM: ${VRAM_MB} MB"
    else
        log_info "AMD GPU detected; installing ROCm stack…"
        apt_install_if_missing curl
        apt_update_once
        # Add the AMDGPU installer from AMD's official repo.
        AMDGPU_DEB="amdgpu-install_6.1.60103-1_all.deb"
        AMDGPU_URL="https://repo.radeon.com/amdgpu-install/6.1.3/ubuntu/jammy/${AMDGPU_DEB}"
        run curl -fsSLo "/tmp/${AMDGPU_DEB}" "$AMDGPU_URL"
        run sudo apt-get install -y -qq "/tmp/${AMDGPU_DEB}"
        run sudo amdgpu-install -y --usecase=rocm --no-dkms
        run sudo usermod -aG render,video "$USER"
        if has rocm-smi && rocm-smi &>/dev/null; then
            VRAM_BYTES=$(rocm-smi --showmeminfo vram 2>/dev/null \
                         | grep -i 'Total Memory' | awk '{print $NF}' | head -1 || echo "0")
            VRAM_MB=$(( VRAM_BYTES / 1024 / 1024 ))
            log_info "ROCm active — VRAM: ${VRAM_MB} MB"
        else
            # Try sysfs fallback.
            SYSFS_VRAM=$(cat /sys/class/drm/card0/device/mem_info_vram_total 2>/dev/null || echo "0")
            if [[ "$SYSFS_VRAM" -gt 0 ]]; then
                VRAM_MB=$(( SYSFS_VRAM / 1024 / 1024 ))
                log_warn "ROCm installed — reboot required for full activation."
                log_warn "Estimated VRAM from sysfs: ${VRAM_MB} MB"
            else
                log_warn "VRAM could not be determined — will use RAM-based profile."
            fi
            NEEDS_REBOOT=1
        fi
    fi

else
    log_info "No discrete GPU detected — CPU/RAM inference mode."
fi

# ── 4. Model & context selection ──────────────────────────────────────────────
log_section "4/12  Model & context selection"

# VRAM usage model (practical approximations):
#   nomic-embed-text: ~274 MB (always loaded alongside LLM)
#   llama3.2:1b  (Q4_K_M): weights ~900 MB  + ~82 MB per 1024 ctx tokens
#   llama3.2     (Q4_K_M): weights ~2000 MB  + ~120 MB per 1024 ctx tokens
#   llama3.1:8b  (Q4_K_M): weights ~4800 MB  + ~256 MB per 1024 ctx tokens
#
# Effective budget = available_MB * 0.90 (10% headroom for Ollama overhead)
# Total needed     = embed_MB + llm_weights_MB + (kv_per_1k * ctx_k)
#
# For RAM-only profiles the same formulas apply but we use 0.55 of total RAM
# to leave room for the OS, Docker containers, and the Spring Boot JVM.

NOMIC_MB=274

select_profile() {
    local budget=$1  # MB
    local use_vram=$2  # 1 = GPU, 0 = CPU
    : "$use_vram"

    # Candidate profiles: (model tag, ctx, total_MB_needed)
    # Ordered from most capable to least; first one that fits wins.
    local profiles=(
        "llama3.1:8b 16384 $(( 4800 + NOMIC_MB + 256*16 ))"
        "llama3.1:8b  8192 $(( 4800 + NOMIC_MB + 256*8  ))"
        "llama3.1:8b  4096 $(( 4800 + NOMIC_MB + 256*4  ))"
        "llama3.1:8b  2048 $(( 4800 + NOMIC_MB + 256*2  ))"
        "llama3.2     8192 $(( 2000 + NOMIC_MB + 120*8  ))"
        "llama3.2     4096 $(( 2000 + NOMIC_MB + 120*4  ))"
        "llama3.2     2048 $(( 2000 + NOMIC_MB + 120*2  ))"
        "llama3.2:1b  2048 $(( 900  + NOMIC_MB + 82*2   ))"
        "llama3.2:1b  1024 $(( 900  + NOMIC_MB + 82*1   ))"
    )

    for entry in "${profiles[@]}"; do
        local model ctx needed
        read -r model ctx needed <<< "$entry"
        if [[ $budget -ge $needed ]]; then
            echo "$model $ctx"
            return
        fi
    done

    # Absolute minimum fallback.
    echo "llama3.2:1b 512"
}

if [[ $VRAM_MB -gt 0 ]]; then
    # GPU path: budget = 90% of VRAM.
    BUDGET=$(( VRAM_MB * 90 / 100 ))
    PROFILE=$(select_profile "$BUDGET" 1)
    PROFILE_SOURCE="VRAM (${VRAM_MB} MB)"
else
    # CPU/RAM path: budget = 55% of RAM.
    BUDGET=$(( RAM_MB * 55 / 100 ))
    PROFILE=$(select_profile "$BUDGET" 0)
    PROFILE_SOURCE="RAM (${RAM_MB} MB)"
fi

read -r OLLAMA_MODEL OLLAMA_NUM_CTX <<< "$PROFILE"

log_info "Memory source  : ${PROFILE_SOURCE}"
log_info "Budget used    : ${BUDGET} MB"
log_info "Selected model : ${OLLAMA_MODEL}"
log_info "Context window : ${OLLAMA_NUM_CTX} tokens"

# ── 5. Docker & Docker Compose ────────────────────────────────────────────────
log_section "5/12  Docker"

if has docker && docker compose version &>/dev/null 2>&1; then
    DOCKER_VER=$(docker --version | grep -oP '\d+\.\d+\.\d+' | head -1)
    log_info "Docker ${DOCKER_VER} (with Compose plugin) — already installed"
else
    log_info "Installing Docker…"
    apt_install_if_missing ca-certificates curl gnupg lsb-release
    run sudo install -m 0755 -d /etc/apt/keyrings
    run sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        -o /etc/apt/keyrings/docker.asc
    run sudo chmod a+r /etc/apt/keyrings/docker.asc
    ARCH=$(dpkg --print-architecture)
    CODENAME=$(. /etc/os-release && echo "$VERSION_CODENAME")
    run_shell "echo 'deb [arch=${ARCH} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${CODENAME} stable' | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null"
    APT_UPDATED=0  # force refresh after adding new repo
    apt_update_once
    run sudo apt-get install -y -qq \
        docker-ce docker-ce-cli containerd.io \
        docker-buildx-plugin docker-compose-plugin
    run sudo usermod -aG docker "$USER"
    log_warn "Added $USER to the docker group — log out and back in for it to take effect."
    log_info "Docker installed"
fi

# ── 6. Java 21 ────────────────────────────────────────────────────────────────
log_section "6/12  Java 21"

JAVA_MIN=21

if has java && has javac && [[ $(java_version) -ge $JAVA_MIN ]] && [[ $(javac_version) -ge $JAVA_MIN ]]; then
    log_info "Java $(java_version) / javac $(javac_version) — already installed"
else
    log_info "Installing Eclipse Temurin JDK 21…"
    apt_install_if_missing wget apt-transport-https gpg
    run_shell "wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor | sudo tee /etc/apt/keyrings/adoptium.gpg > /dev/null"
    CODENAME=$(. /etc/os-release && echo "$VERSION_CODENAME")
    run_shell "echo 'deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${CODENAME} main' | sudo tee /etc/apt/sources.list.d/adoptium.list > /dev/null"
    APT_UPDATED=0
    apt_update_once
    run sudo apt-get install -y -qq temurin-21-jdk
    log_info "Java 21 installed"
fi

# Ensure JAVA_HOME is set in the current shell.
if [[ -z "${JAVA_HOME:-}" ]] && JAVA_HOME_CANDIDATE=$(find_java_home); then
    JAVA_HOME=$JAVA_HOME_CANDIDATE
    export JAVA_HOME
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# ── 7. Maven ──────────────────────────────────────────────────────────────────
log_section "7/12  Maven"

MVN_MIN_MAJOR=3
MVN_MIN_MINOR=9
MVN_LOCAL_DIR="$ROOT/.maven"
MVN_LOCAL_BIN="$MVN_LOCAL_DIR/bin/mvn"

mvn_version_ok() {
    local cmd=$1
    local maj min
    maj=$("$cmd" --version 2>&1 | grep -oP 'Apache Maven \K[0-9]+' | head -1)
    min=$("$cmd" --version 2>&1 | grep -oP 'Apache Maven [0-9]+\.\K[0-9]+' | head -1)
    [[ "${maj:-0}" -ge "$MVN_MIN_MAJOR" && "${min:-0}" -ge "$MVN_MIN_MINOR" ]]
}

if has mvn && mvn_version_ok mvn; then
    MVN_CMD="mvn"
    log_info "System mvn $(mvn --version 2>&1 | grep -oP 'Apache Maven \K[\d.]+') — using it"
elif [[ -f "$MVN_LOCAL_BIN" ]] && mvn_version_ok "$MVN_LOCAL_BIN"; then
    MVN_CMD="$MVN_LOCAL_BIN"
    log_info "Local mvn ${MVN_LOCAL_DIR} — using it"
else
    MVN_VERSION="3.9.9"
    log_info "Downloading Maven ${MVN_VERSION} locally (no system install)…"
    apt_install_if_missing curl tar
    run mkdir -p "$MVN_LOCAL_DIR"
    run curl -fsSL \
        "https://archive.apache.org/dist/maven/maven-3/${MVN_VERSION}/binaries/apache-maven-${MVN_VERSION}-bin.tar.gz" \
        -o /tmp/maven.tar.gz
    run tar -xzf /tmp/maven.tar.gz -C "$MVN_LOCAL_DIR" --strip-components=1
    run rm -f /tmp/maven.tar.gz
    MVN_CMD="$MVN_LOCAL_BIN"
    log_info "Maven ${MVN_VERSION} extracted to .maven/ (not added to system PATH)"
fi

# ── 8. Python 3.12 ────────────────────────────────────────────────────────────
log_section "8/12  Python"

PYTHON_MIN=11   # 3.11 or newer

if has python3 && [[ $(python_version) -ge $PYTHON_MIN ]]; then
    PYTHON_CMD="python3"
    log_info "Python 3.$(python_version) — already installed"
else
    log_info "Installing Python 3.12…"
    apt_update_once
    run sudo apt-get install -y -qq software-properties-common
    run sudo add-apt-repository -y ppa:deadsnakes/ppa
    APT_UPDATED=0
    apt_update_once
    run sudo apt-get install -y -qq python3.12 python3.12-venv python3.12-dev
    PYTHON_CMD="python3.12"
    log_info "Python 3.12 installed"
fi

# ── 9. Node.js 20 ────────────────────────────────────────────────────────────
log_section "9/12  Node.js"

NODE_MIN=20

if has node && [[ $(node_version) -ge $NODE_MIN ]]; then
    log_info "Node $(node --version) — already installed"
else
    log_info "Installing Node.js ${NODE_MIN} LTS via NodeSource…"
    apt_install_if_missing curl ca-certificates gnupg
    run_shell "curl -fsSL https://deb.nodesource.com/setup_${NODE_MIN}.x | sudo -E bash -"
    APT_UPDATED=0
    apt_update_once
    run sudo apt-get install -y -qq nodejs
    if has node; then
        log_info "Node $(node --version) installed"
    else
        log_info "Node.js ${NODE_MIN} installation scheduled"
    fi
fi

# ── 10. Ollama ────────────────────────────────────────────────────────────────
log_section "10/12  Ollama"

if ! has curl; then
    log_info "Installing curl for Ollama setup and health checks…"
    apt_install_if_missing curl
fi

if has ollama; then
    log_info "Ollama already installed: $(ollama --version 2>/dev/null || echo 'version unknown')"
else
    log_info "Installing Ollama…"
    apt_install_if_missing curl
    run_shell "curl -fsSL https://ollama.com/install.sh | sh"
    log_info "Ollama installed"
fi

# Start ollama serve in the background if not already responding.
OLLAMA_API="http://localhost:11434"
if ! curl -sf "${OLLAMA_API}" &>/dev/null; then
    log_info "Starting Ollama server in the background…"
    if [[ $DRY_RUN -eq 0 ]]; then
        nohup ollama serve > /tmp/ollama-init.log 2>&1 &
        OLLAMA_SERVE_PID=$!
        printf "    Waiting for Ollama API"
        for _ in $(seq 1 30); do
            if curl -sf "${OLLAMA_API}" &>/dev/null; then break; fi
            printf "."; sleep 1
        done
        if curl -sf "${OLLAMA_API}" &>/dev/null; then
            echo -e " ${GREEN}ready (PID ${OLLAMA_SERVE_PID})${NC}"
        else
            echo ""
            log_warn "Ollama API did not respond — model pulls may fail. Check /tmp/ollama-init.log"
        fi
    else
        echo -e "${YELLOW}[dry-run]${NC} ollama serve &"
    fi
else
    log_info "Ollama API already responding on ${OLLAMA_API}"
fi

# Pull required models in the background (non-blocking).
pull_model_bg() {
    local model=$1
    if [[ $DRY_RUN -eq 1 ]]; then
        echo -e "${YELLOW}[dry-run]${NC} ollama pull ${model} &"
        return
    fi
    if ollama list 2>/dev/null | grep -q "^${model}"; then
        log_info "Model '${model}' already present — skipping pull"
    else
        log_info "Pulling '${model}' in background (check /tmp/ollama-pull-${model//[:\/]/-}.log)…"
        nohup ollama pull "$model" \
            > "/tmp/ollama-pull-${model//[:\/]/-}.log" 2>&1 &
    fi
}

pull_model_bg "$OLLAMA_MODEL"
pull_model_bg "nomic-embed-text"

# ── 11. Python venv + dependencies ────────────────────────────────────────────
log_section "11/12  Python inference-engine setup"

IE_DIR="$ROOT/inference-engine"

if [[ $FORCE -eq 1 && -d "$IE_DIR/venv" ]]; then
    log_info "--force: removing existing venv"
    run rm -rf "$IE_DIR/venv"
fi

if [[ ! -d "$IE_DIR/venv" ]]; then
    log_info "Creating Python virtual environment…"
    run "$PYTHON_CMD" -m venv "$IE_DIR/venv"
fi

log_info "Installing Python dependencies…"
run "$IE_DIR/venv/bin/pip" install --quiet --upgrade pip
run "$IE_DIR/venv/bin/pip" install --quiet -r "$IE_DIR/requirements.txt"
log_info "Python dependencies installed"

# ── 11b. Frontend npm install ─────────────────────────────────────────────────
FE_DIR="$ROOT/frontend"
if [[ -d "$FE_DIR" ]]; then
    log_info "Installing frontend dependencies…"
    if [[ $FORCE -eq 1 || ! -d "$FE_DIR/node_modules" ]]; then
        run npm --prefix "$FE_DIR" install --silent
        log_info "Frontend dependencies installed"
    else
        log_info "node_modules already present — skipping (use --force to re-run)"
    fi
fi

# ── 12. gRPC stub generation ──────────────────────────────────────────────────
log_section "12/12  gRPC stubs"

PY_PB2="$IE_DIR/ai_service_pb2.py"
PY_GRPC="$IE_DIR/ai_service_pb2_grpc.py"

if [[ $SKIP_STUBS -eq 1 ]]; then
    log_info "--skip-stubs: skipping gRPC code generation"
elif [[ $FORCE -eq 0 && -f "$PY_PB2" && -f "$PY_GRPC" ]]; then
    log_info "Python stubs already exist — skipping (use --force to regenerate)"
else
    log_info "Generating Python gRPC stubs from shared-protos/…"
    run "$IE_DIR/venv/bin/python" -m grpc_tools.protoc \
        -I "$ROOT/shared-protos" \
        --python_out="$IE_DIR" \
        --grpc_python_out="$IE_DIR" \
        "$ROOT/shared-protos/ai_service.proto"
    log_info "Python stubs generated"
fi

log_info "Compiling Java stubs and control-plane classes…"
run "$MVN_CMD" -f "$ROOT/control-plane/pom.xml" \
    clean compile -B -q \
    -Dprotobuf.source.dir="$ROOT/shared-protos"
log_info "Java compilation successful"

# ── Write .env ────────────────────────────────────────────────────────────────
ENV_FILE="$ROOT/.env"
write_env() {
    cat > "$ENV_FILE" <<EOF
# Generated by init.sh on $(date -u '+%Y-%m-%dT%H:%M:%SZ')
# Hardware profile: ${PROFILE_SOURCE}
# Budget: ${BUDGET} MB   Model: ${OLLAMA_MODEL}   Context: ${OLLAMA_NUM_CTX}

# ── Ollama ──────────────────────────────────────────────────────────────────
OLLAMA_MODEL=${OLLAMA_MODEL}
OLLAMA_NUM_CTX=${OLLAMA_NUM_CTX}

# ── Inference Engine ────────────────────────────────────────────────────────
OLLAMA_HOST=http://localhost:11434

# ── Vector DB ───────────────────────────────────────────────────────────────
QDRANT_HOST=localhost
QDRANT_PORT=6333
EOF
    log_info ".env written (model=${OLLAMA_MODEL}, num_ctx=${OLLAMA_NUM_CTX})"
}

if [[ $DRY_RUN -eq 1 ]]; then
    log_info "[dry-run] Would write .env with OLLAMA_MODEL=${OLLAMA_MODEL} OLLAMA_NUM_CTX=${OLLAMA_NUM_CTX}"
elif [[ -f "$ENV_FILE" && $FORCE -eq 0 ]]; then
    log_warn ".env already exists — skipping (use --force to overwrite)"
    log_warn "Recommended values: OLLAMA_MODEL=${OLLAMA_MODEL}  OLLAMA_NUM_CTX=${OLLAMA_NUM_CTX}"
else
    write_env
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}  ╔════════════════════════════════════════════════════╗"
echo   "  ║                 Initialisation Summary                ║"
echo -e "  ╚════════════════════════════════════════════════════╝${NC}"
echo ""
summary_row "OS"            "${PRETTY_NAME:-unknown}"
summary_row "GPU vendor"    "${GPU_VENDOR}"
summary_row "VRAM"          "${VRAM_MB} MB"
summary_row "System RAM"    "${RAM_MB} MB"
summary_row "Memory budget" "${BUDGET} MB  (${PROFILE_SOURCE})"
summary_row "Ollama model"  "${OLLAMA_MODEL}"
summary_row "Context size"  "${OLLAMA_NUM_CTX} tokens"
summary_row "Java"          "$(describe_command java -version)"
summary_row "Maven cmd"     "${MVN_CMD}"
summary_row "Python"        "$(describe_command "$PYTHON_CMD" --version)"
summary_row "Node.js"       "$(describe_command node --version)"
summary_row "Ollama"        "$(describe_command ollama --version)"
echo ""

if [[ $NEEDS_REBOOT -eq 1 ]]; then
    echo -e "${YELLOW}  ┌──────────────────────────────────────────────────┐"
    echo   "  │  GPU drivers were installed — please REBOOT before │"
    echo   "  │  starting the stack to activate GPU acceleration.  │"
    echo -e "  └──────────────────────────────────────────────────┘${NC}"
    echo ""
fi

echo -e "${BOLD}  Next steps:${NC}"
echo   "    Containerised stack  →  docker compose up --build"
echo   "    Local dev mode       →  ./start.sh"
echo ""
echo -e "${GREEN}  ✓  Initialisation complete.${NC}"
echo ""
