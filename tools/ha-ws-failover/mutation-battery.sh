#!/usr/bin/env bash
# Mutation battery for the HA WebSocket address-family failover lane.
#
# Each mutation breaks ONE property of the shipped factory/resolver in an isolated throwaway
# extraction of the exact commit under test, runs the focused enforced suite there, and credits a
# kill ONLY when the mutation's NAMED assertion turns red. Verdicts are distinct on purpose:
#   KILLED            the named assertion failed
#   WRONG-TEST-RED    something failed, but not the named assertion
#   SURVIVED          suite green (a finding for a real mutation; required for the control)
#   MUTATION-ABSENT   the sed matched nothing — proves nothing
#   MUTATION-BROKE-BUILD  compilation failed — the property was never exercised
#   INFRA-FAILURE     daemon death / timeout / launch failure — rerun alone, not evidence
#
# Never mutates in place: a killed in-place run would leave its mutation applied.
set -uo pipefail

SRC="${1:?source tree required}"
OUT="${2:?output dir required}"
mkdir -p "$OUT"

TESTS=(
  --tests 'io.github.maxlyth.hapaneld.util.FamilyPlannedDnsTest'
  --tests 'io.github.maxlyth.hapaneld.util.HaWebSocketClientsFailoverTest'
  --tests 'io.github.maxlyth.hapaneld.util.HaWebSocketClientsTlsTest'
)

# name @@ file @@ sed expression @@ named assertion (test method)
MUTATIONS=(
  'policy-prefer-ignored@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/val ipv4Leads = preferIpv4 || all.firstOrNull() is Inet4Address/val ipv4Leads = all.firstOrNull() is Inet4Address/@@preferIpv4LeadsWithARecordsAndKeepsEveryAddress'
  'force-empty-guard-dead@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/if (v4.isEmpty()) {/if (false) {/@@forceIpv4OnAnIpv6OnlyHostFailsWithAClearVerdict'
  'force-filter-dropped@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/            return v4$/            return all/@@forceIpv4NeverEmitsAnIpv6Address'
  'interleave-becomes-concat@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/if (leadIterator.hasNext()) interleaved.add(leadIterator.next())/while (leadIterator.hasNext()) interleaved.add(leadIterator.next())/@@aDeadLeadingFamilyCostsExactlyOneRouteBeforeTheSibling'
  'trail-family-dropped@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/while (leadIterator.hasNext() || trailIterator.hasNext())/while (leadIterator.hasNext())/@@everyAddressSurvivesWhenTheTrailingFamilyIsLarger'
  'resolver-injection-dropped@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/systemLookup = resolver)/systemLookup = { Dns.SYSTEM.lookup(it) })/@@refusedRouteFallsBackToTheNextAddress'
  'connect-timeout-unbounded@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/connectTimeout(routeConnectTimeoutMs, TimeUnit.MILLISECONDS)/connectTimeout(0, TimeUnit.MILLISECONDS)/@@aDeadOnlyRouteFailsWithinTheConfiguredConnectTimeout'
  'fast-fallback-disabled@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/fastFallback(true)/fastFallback(false)/@@blackHoledRouteStillReachesTheLiveSiblingWithinTheCallerDeadline'
  'tls-trust-not-applied@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/if (tls != null) sslSocketFactory/if (tls != null \&\& false) sslSocketFactory/@@aMatchingHostnameCompletesTheTlsUpgrade'
  'control-comment-only@@app/src/main/kotlin/io/github/maxlyth/hapaneld/util/HaWebSocketClients.kt@@s/One shared construction path/One shared, control-touched construction path/@@CONTROL'
)

overall=0
for entry in "${MUTATIONS[@]}"; do
  NAME="${entry%%@@*}"; rest="${entry#*@@}"
  FILE="${rest%%@@*}"; rest="${rest#*@@}"
  SED_EXPR="${rest%@@*}"; ASSERTION="${rest##*@@}"
  WT="$(mktemp -d "/tmp/mut-ws-${NAME}-XXXXXX")"
  git -C "$SRC" archive HEAD | tar -x -C "$WT"

  before="$(sha256sum "$WT/$FILE" | cut -d' ' -f1)"
  sed -i "$SED_EXPR" "$WT/$FILE"
  after="$(sha256sum "$WT/$FILE" | cut -d' ' -f1)"
  if [ "$before" = "$after" ]; then
    printf '%s: MUTATION-ABSENT (sed matched nothing — this proves nothing)\n' "$NAME"
    overall=1; rm -rf "$WT"; continue
  fi

  ( cd "$WT" && timeout 1500 ./gradlew -q :app:testDebugUnitTest "${TESTS[@]}" ) \
    > "$OUT/$NAME.log" 2>&1
  status=$?

  results_dir="$WT/app/build/test-results/testDebugUnitTest"
  if grep -qE 'Compilation error|Could not resolve|compileDebugKotlin.*FAILED|error: ' "$OUT/$NAME.log"; then
    printf '%s: MUTATION-BROKE-BUILD (property never exercised)\n' "$NAME"; overall=1
  elif [ "$status" -eq 124 ] || ! ls "$results_dir"/TEST-*.xml >/dev/null 2>&1; then
    printf '%s: INFRA-FAILURE (exit=%d, no results) — rerun alone\n' "$NAME" "$status"
    tail -5 "$OUT/$NAME.log" | sed 's/^/    /'
    overall=1
  elif [ "$ASSERTION" = "CONTROL" ]; then
    if [ "$status" -eq 0 ]; then printf '%s: CONTROL-OK (suite green under a no-op edit)\n' "$NAME"
    else printf '%s: CONTROL-RED (harness failed a no-op edit — battery is not evidence)\n' "$NAME"; overall=1
    fi
  else
    named_red="$(grep -l "testcase name=\"$ASSERTION\"" "$results_dir"/TEST-*.xml 2>/dev/null \
      | xargs -r grep -A2 "testcase name=\"$ASSERTION\"" | grep -c '<failure\|<error')"
    if [ "${named_red:-0}" -ge 1 ]; then
      printf '%s: KILLED by named assertion %s\n' "$NAME" "$ASSERTION"
    elif [ "$status" -ne 0 ]; then
      printf '%s: WRONG-TEST-RED (suite failed but %s stayed green)\n' "$NAME" "$ASSERTION"; overall=1
    else
      printf '%s: SURVIVED (named assertion cannot fail — a finding, not a pass)\n' "$NAME"; overall=1
    fi
  fi
  rm -rf "$WT"
done
exit "$overall"
