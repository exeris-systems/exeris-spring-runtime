#!/usr/bin/env bash
#
# ADR-067 binary-neutrality gate (supplements ADR-028 obligation 2).
#
# The dual matrix (obligation 2) proves the *source* compiles and tests on both Spring Boot lines.
# It cannot prove what we actually ship. `deploy.yml` runs `mvn clean deploy` with no profile, so
# the published jar is built by the default `matrix-sb3` profile — one SB3-compiled binary that
# ADR-028 then claims runs on SB4 too. Source compatibility does not imply binary compatibility:
# a call site can compile on both lines and bind to a *different descriptor* on each.
#
# Two real instances, both green on the dual matrix and both fatal at runtime on SB4:
#
#   HttpHeaders.putAll(entity.getHeaders())
#     SB3 -> putAll:(Ljava/util/Map;)V              SB4 -> putAll:(Lorg/springframework/http/HttpHeaders;)V
#     Spring Framework 7 stops HttpHeaders implementing MultiValueMap, so the SB3-compiled call
#     hands a non-Map to a Map parameter: IncompatibleClassChangeError on every ResponseEntity
#     return value in Compatibility Mode.
#
#   HttpHeaders.get(headerName)
#     SB3 -> get:(Ljava/lang/Object;)Ljava/util/List;   SB4 -> get:(Ljava/lang/String;)Ljava/util/List;
#     NoSuchMethodError on any @RequestHeader resolving multiple values.
#
# This script compiles the reactor under both profiles and compares every constant-pool reference
# our classes make into org/springframework/**. Any difference means the published SB3 jar would
# call something that resolves differently on SB4, and the build fails naming the call site.
#
# Local use: ./.github/scripts/spring-binary-neutrality.sh
#
# Note for local runs: this installs the reactor twice and finishes on the SB4 line, so your ~/.m2
# holds SB4-built modules afterwards. A subsequent default (SB3) build that is not `clean` can then
# link against them — the stale-class trap documented in spring-boot-4-matrix.md. The script prints a
# reminder at the end; CI is unaffected, its workspace is fresh.
#
set -euo pipefail

cd "$(dirname "$0")/../.."
WORK="${RUNNER_TEMP:-/tmp}/spring-binary-neutrality"
mkdir -p "$WORK"

MVN=(mvn -B -ntp -s .github/maven-settings.xml)

# Emit "<module>/<class>  <springOwner>.<member>:<descriptor>" for every reference into Spring.
# Only target/classes — test classes are compiled and run per line, so they never ship.
#
# The inner pipeline ends in `|| true` because most classes reference no Spring member at all and
# `grep` exits 1 on no match, which `set -e` plus `pipefail` would otherwise treat as a failure. The
# emptiness that `|| true` now hides is caught by the non-empty assertion at the call site instead —
# without it, a broken javap would produce two empty fingerprints, an empty diff, and a vacuous pass.
fingerprint() {
  local out=$1
  local dir mod
  for dir in $(find . -path '*/target/classes' -type d | LC_ALL=C sort); do
    mod=$(echo "$dir" | cut -d/ -f2)
    (cd "$dir" && find . -name '*.class' | sed 's|^\./||; s|\.class$||; s|/|.|g') \
      | while read -r cls; do
          javap -v -p -cp "$dir" "$cls" 2>/dev/null \
            | grep -oE '(Method|InterfaceMethod|Field)ref +#[0-9]+\.#[0-9]+ +// +org/springframework/\S+' \
            | grep -oE 'org/springframework/\S+' \
            | LC_ALL=C sort -u \
            | sed "s|^|$mod/$cls  |" || true
        done
  done | LC_ALL=C sort -u > "$out"
}

# A reactor this size cannot legitimately reference Spring fewer than this many times. The floor turns
# "the scan silently produced nothing" into a failure rather than a clean diff.
MIN_REFS=100

for line in sb3 sb4; do
  echo "==> compiling reactor under matrix-$line"
  "${MVN[@]}" "-Pmatrix-$line" clean install -DskipTests -q
  fingerprint "$WORK/$line.refs"
  found=$(wc -l < "$WORK/$line.refs")
  echo "    $found Spring references"
  if [ "$found" -lt "$MIN_REFS" ]; then
    echo "::error::Only $found Spring references found under matrix-$line (expected >= $MIN_REFS)." >&2
    echo "The scan is broken — an empty fingerprint would make the comparison pass vacuously." >&2
    exit 1
  fi
done

echo "==> comparing"
if diff_out=$(LC_ALL=C comm -3 "$WORK/sb3.refs" "$WORK/sb4.refs") && [ -z "$diff_out" ]; then
  echo "OK — every Spring call site binds to the same descriptor on both lines."
  if [ -z "${CI:-}" ]; then
    echo "note: ~/.m2 now holds SB4-built modules. Run 'mvn -Pmatrix-sb3 clean install' before"
    echo "      going back to normal SB3 work, or a non-clean build may link against them."
  fi
  exit 0
fi

cat >&2 <<'MSG'

::error::ADR-067 binary-neutrality gate failed.

One or more call sites bind to a different Spring descriptor on each matrix line. The dual matrix
stays green because the source compiles on both, but the published SB3-compiled jar will fail at
runtime on SB4 (NoSuchMethodError / IncompatibleClassChangeError).

Fix the call site to use a member whose signature is identical on both lines — diff the two APIs
with javap before assuming a bridge is needed. Column 1 = SB3 only, column 2 = SB4 only.

MSG
LC_ALL=C comm -3 "$WORK/sb3.refs" "$WORK/sb4.refs" >&2
exit 1
