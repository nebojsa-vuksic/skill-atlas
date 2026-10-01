#!/usr/bin/env bash
# Air cloud environment setup for Skill Atlas.
#
# The environment has Java 25, git and python3 but no sudo, so everything else lives in $HOME:
# - Gradle and the JVMs it starts are pointed at the egress proxy (Java ignores HTTPS_PROXY).
# - Headless Chromium's system libraries and fonts come from Ubuntu .debs extracted without
#   installing them, which is what `installPlaywrightChromium -PplaywrightWithDeps` does with sudo.
# - In warmup mode the full `./gradlew build` runs, which fetches the Gradle distribution, the
#   JDK 21 toolchain, all dependencies and Chromium into the snapshot, and proves the tests pass.
set -euo pipefail

if [ "${AIR_STARTUP_MODE:-}" = warmup ]; then WARMUP=1; else WARMUP=; fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPS_DIR="$HOME/.local/chromium-deps"
ENV_FILE="$HOME/.skill-atlas-env.sh"
MARKER="# skill-atlas air env"

log() { echo "[startup] $*"; }

# Chromium's runtime libraries (as reported missing by ldd) plus fontconfig and fonts; without
# fonts every line renders with zero height and pages crash.
CHROMIUM_PACKAGES="libnspr4 libnss3 libatk1.0-0t64 libatk-bridge2.0-0t64 libdbus-1-3 libx11-6
  libxcomposite1 libxdamage1 libxext6 libxfixes3 libxrandr2 libgbm1 libxcb1 libxkbcommon0
  libasound2t64 libatspi2.0-0t64 libxau6 libxdmcp6 libxrender1 libwayland-server0 libdrm2 libxi6
  libbsd0 libmd0 libxshmfence1 libdrm-common libfontconfig1 fontconfig-config fonts-liberation
  fonts-dejavu-core fonts-freefont-ttf"

proxy_settings() {
    # Prints "host port" for the egress proxy, or nothing when there is none.
    local proxy="${HTTPS_PROXY:-${https_proxy:-}}"
    [ -n "$proxy" ] || return 0
    proxy="${proxy#*://}"
    proxy="${proxy##*@}"
    proxy="${proxy%%/*}"
    echo "${proxy%:*} ${proxy##*:}"
}

configure_gradle_proxy() {
    local settings host port props="$HOME/.gradle/gradle.properties"
    settings="$(proxy_settings)"
    [ -n "$settings" ] || { log "no HTTPS_PROXY, leaving Gradle proxy unset"; return 0; }
    read -r host port <<<"$settings"
    mkdir -p "$HOME/.gradle"
    touch "$props"
    # Replace only our own block, so other user settings survive.
    sed -i "/^$MARKER start/,/^$MARKER end/d" "$props"
    cat >>"$props" <<EOF
$MARKER start
systemProp.http.proxyHost=$host
systemProp.http.proxyPort=$port
systemProp.https.proxyHost=$host
systemProp.https.proxyPort=$port
systemProp.http.nonProxyHosts=localhost|127.0.0.1|::1
$MARKER end
EOF
    log "Gradle uses proxy $host:$port"
}

install_chromium_deps() {
    local stamp="$DEPS_DIR/.packages"
    if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$CHROMIUM_PACKAGES" ]; then
        log "Chromium system libraries already extracted"
    else
        log "downloading Chromium system libraries and fonts"
        rm -rf "$DEPS_DIR"
        mkdir -p "$DEPS_DIR/debs" "$DEPS_DIR/root"
        # shellcheck disable=SC2086
        (cd "$DEPS_DIR/debs" && apt-get download $CHROMIUM_PACKAGES)
        for deb in "$DEPS_DIR"/debs/*.deb; do dpkg -x "$deb" "$DEPS_DIR/root"; done
        rm -rf "$DEPS_DIR/debs"
        echo "$CHROMIUM_PACKAGES" >"$stamp"
        log "Chromium system libraries extracted to $DEPS_DIR/root"
    fi
    mkdir -p "$DEPS_DIR/fontconfig"
    cat >"$DEPS_DIR/fontconfig/fonts.conf" <<EOF
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>$DEPS_DIR/root/usr/share/fonts</dir>
  <cachedir>$DEPS_DIR/fontconfig/cache</cachedir>
</fontconfig>
EOF
}

write_env_file() {
    local settings host port opts=""
    settings="$(proxy_settings)"
    if [ -n "$settings" ]; then
        read -r host port <<<"$settings"
        opts="-Dhttps.proxyHost=$host -Dhttps.proxyPort=$port -Dhttp.proxyHost=$host -Dhttp.proxyPort=$port -Dhttp.nonProxyHosts=localhost|127.0.0.1|::1"
    fi
    cat >"$ENV_FILE" <<EOF
# Written by .air/cloud/startup.sh.
export LD_LIBRARY_PATH="$DEPS_DIR/root/usr/lib/x86_64-linux-gnu:$DEPS_DIR/root/lib/x86_64-linux-gnu\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
export FONTCONFIG_FILE="$DEPS_DIR/fontconfig/fonts.conf"
# The installed launcher passes these to the JVM, so real scans reach GitHub through the proxy.
export SKILL_ATLAS_OPTS="$opts"
EOF
    # A login shell reads only the first of these that exists.
    local profile="$HOME/.profile" candidate
    for candidate in "$HOME/.bash_profile" "$HOME/.bash_login" "$HOME/.profile"; do
        if [ -f "$candidate" ]; then profile="$candidate"; break; fi
    done
    local rc
    for rc in "$profile" "$HOME/.bashrc"; do
        touch "$rc"
        grep -qF "$MARKER" "$rc" || printf '\n%s\n[ -f "%s" ] && . "%s"\n' "$MARKER" "$ENV_FILE" "$ENV_FILE" >>"$rc"
    done
    log "environment written to $ENV_FILE"
}

healthcheck() {
    # The definition of done: unit, CLI integration, pty and browser tests all pass.
    log "healthcheck: ./gradlew build (this also warms the Gradle, JDK and Chromium caches)"
    cd "$REPO_DIR"
    if ./gradlew build --no-daemon --console=plain; then
        log "healthcheck: build passed"
    else
        log "healthcheck: build FAILED, see build/reports/tests/"
        return 1
    fi
    "$REPO_DIR/build/install/skill-atlas/bin/skill-atlas" --version
}

configure_gradle_proxy
install_chromium_deps
write_env_file
# shellcheck disable=SC1090
. "$ENV_FILE"
chmod +x "$REPO_DIR/gradlew"

if [ -n "$WARMUP" ]; then healthcheck; fi
log "done"
