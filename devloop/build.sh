#!/usr/bin/env bash
# Builds the extended dev-loop daemon jar with plain javac + jar: no Maven build,
# no dependencies. The daemon itself is JDK-only (upstream enforces it); its
# Maven build extension (mavenext/, used when the app runs through a Maven goal)
# compiles against the jars of a local Maven installation - the one ./mvnw
# downloads is enough - and only needs them at build time.
#
# Use it by pointing the stock vaadin-dev client at the jar:
#   VAADIN_DEV_DAEMON_OPTS="-Dvaadin.dev.daemonJar=$PWD/target/flow-devloop-daemon-x.jar" \
#     .vaadin/vaadin-dev start
# or just use ./vdx (or .vaadin/vaadin-dev, which hands off to it).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$here/target"

maven_lib() {
    local mvn dir candidates=()
    [[ -n "${MAVEN_HOME:-}" ]] && candidates+=("$MAVEN_HOME/lib")
    if mvn="$(command -v mvn)"; then
        candidates+=("$(dirname "$(readlink -f "$mvn")")/../lib")
    fi
    # The distribution ./mvnw unpacks; download it with `./mvnw -v` if missing.
    candidates+=($(ls -d "${MAVEN_USER_HOME:-$HOME/.m2}"/wrapper/dists/*/*/lib 2>/dev/null | sort -r))
    for dir in ${candidates[@]+"${candidates[@]}"}; do
        if compgen -G "$dir/maven-core-*.jar" >/dev/null; then
            echo "$dir"; return
        fi
    done
    # Last resort, for an mvn behind a shim or wrapper script: ask it.
    if [[ -n "$mvn" ]]; then
        dir="$(mvn -v 2>/dev/null | sed -n 's/^Maven home: //p')/lib"
        compgen -G "$dir/maven-core-*.jar" >/dev/null && echo "$dir"
    fi
}
lib="$(maven_lib || true)"
if [[ -z "$lib" && -x "$here/../mvnw" ]]; then
    (cd "$here/.." && ./mvnw -q -v >/dev/null 2>&1) || true
    lib="$(maven_lib || true)"
fi
if [[ -z "$lib" ]]; then
    echo "build.sh: no Maven installation found to compile against; run ./mvnw -v once" >&2
    exit 69
fi

rm -rf "$out/classes" && mkdir -p "$out/classes"
javac --release 21 -nowarn -cp "$lib/*" -d "$out/classes" $(find "$here/daemon/src/main/java" -name '*.java')
cp -r "$here/daemon/src/main/resources/." "$out/classes/"
cat > "$out/MANIFEST.MF" <<MF
Main-Class: com.vaadin.flow.devloop.daemon.Daemon
Premain-Class: com.vaadin.flow.devloop.agent.DevLoopAgent
Agent-Class: com.vaadin.flow.devloop.agent.DevLoopAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
MF
jar --create --file "$out/flow-devloop-daemon-x.jar" --manifest "$out/MANIFEST.MF" -C "$out/classes" .
echo "$out/flow-devloop-daemon-x.jar"
