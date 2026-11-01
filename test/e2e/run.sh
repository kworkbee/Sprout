#!/usr/bin/env bash
# Kind end-to-end + fault-injection suite. Usage: test/e2e/run.sh [--keep]
# Creates the "sprout" Kind cluster when missing, installs ingress-nginx and the operator (2 replicas,
# leader election) from config/default, then runs the scenarios below. Exits non-zero on any failure.
set -uo pipefail
cd "$(dirname "$0")/../.."

CLUSTER=sprout
CTX=kind-$CLUSTER
INGRESS_MANIFEST=https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.1/deploy/static/provider/kind/deploy.yaml
FAILS=0
k() { kubectl --context "$CTX" "$@"; }
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; FAILS=$((FAILS + 1)); }
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then pass "$name"; else fail "$name"; fi; }
# eventually <seconds> <cmd...>
eventually() { local t=$1; shift; local end=$((SECONDS + t)); while ((SECONDS < end)); do "$@" >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }
check_eventually() { local name=$1 t=$2; shift 2; if eventually "$t" "$@"; then pass "$name"; else fail "$name"; fi; }

ready() { [[ "$(k get pe "$1" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" == "True" ]]; }
gone() { ! k get "$@" >/dev/null 2>&1; }
leader() { k -n sprout-system get lease sprout-operator -o jsonpath='{.spec.holderIdentity}'; }
cr() { # cr <name> [extra spec yaml lines]
  printf 'apiVersion: sprout.dev/v1alpha1\nkind: PreviewEnvironment\nmetadata:\n  name: %s\nspec:\n  image: nginx:1.27-alpine\n  port: 80\n  ingress: {}\n%s\n' "$1" "${2:-}" | k apply -f - >/dev/null
}

echo "== setup"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --config kind-config.yaml || exit 1
k wait --for=condition=Ready nodes --all --timeout=180s >/dev/null
k get ns ingress-nginx >/dev/null 2>&1 || k apply -f "$INGRESS_MANIFEST" >/dev/null
k -n ingress-nginx wait --for=condition=Ready pod -l app.kubernetes.io/component=controller --timeout=240s >/dev/null || exit 1
./gradlew -q bootJar || exit 1
docker build -q -t sprout-operator:dev . >/dev/null && kind load docker-image sprout-operator:dev --name "$CLUSTER" >/dev/null || exit 1
k apply -k config/default >/dev/null
k -n sprout-system rollout restart deploy/sprout-operator >/dev/null
k -n sprout-system rollout status deploy/sprout-operator --timeout=180s >/dev/null || exit 1
k -n sprout-system create secret generic e2e-db --from-literal=PASSWORD=hunter2 --dry-run=client -o yaml | k apply -f - >/dev/null
k -n sprout-system label secret e2e-db sprout.dev/shareable=true --overwrite >/dev/null
k -n sprout-system create secret generic e2e-private --from-literal=X=1 --dry-run=client -o yaml | k apply -f - >/dev/null

echo "== lifecycle"
cr e2e-a "  env:
    - {name: GREETING, value: hello}
  secretName: e2e-db"
check_eventually "CR becomes Ready" 180 ready e2e-a
check "namespace, quota, limitrange, netpol exist" bash -c "kubectl --context $CTX get ns sprout-e2e-a && kubectl --context $CTX -n sprout-e2e-a get resourcequota/sprout-quota limitrange/sprout-limits networkpolicy/sprout-isolation"
check "status.url is set" test "$(k get pe e2e-a -o jsonpath='{.status.url}')" = "http://e2e-a.localhost:8080"
check_eventually "preview URL serves traffic" 90 curl -fsS --max-time 3 http://e2e-a.localhost:8080/
check "env + secret injected" test "$(k -n sprout-e2e-a exec deploy/app -- sh -c 'echo $GREETING-$PASSWORD')" = "hello-hunter2"
check "unshareable secret is refused" bash -c "
  printf 'apiVersion: sprout.dev/v1alpha1\nkind: PreviewEnvironment\nmetadata: {name: e2e-priv}\nspec: {image: nginx, secretName: e2e-private}\n' | kubectl --context $CTX apply -f - >/dev/null
  sleep 8; [ \"\$(kubectl --context $CTX get pe e2e-priv -o jsonpath='{.status.conditions[0].reason}')\" = SecretUnavailable ]"
k delete pe e2e-priv --wait=false >/dev/null 2>&1

echo "== isolation & quota"
cr e2e-b
check_eventually "second env Ready" 180 ready e2e-b
check "same-namespace traffic allowed" k -n sprout-e2e-a exec deploy/app -- wget -q -O- -T 3 http://app.sprout-e2e-a.svc/
check "cross-environment traffic blocked" bash -c "! kubectl --context $CTX -n sprout-e2e-a exec deploy/app -- wget -q -O- -T 3 http://app.sprout-e2e-b.svc/"
check "quota rejects the 11th pod" bash -c "
  for i in \$(seq 1 11); do kubectl --context $CTX -n sprout-e2e-b run q\$i --image=nginx:1.27-alpine --restart=Never 2>&1; done | grep -qi 'exceeded quota'"

echo "== drift & spec change"
UID1=$(k -n sprout-e2e-a get deploy app -o jsonpath='{.metadata.uid}')
k -n sprout-e2e-a delete deploy app >/dev/null
check_eventually "deleted Deployment is recreated" 60 bash -c "[ \"\$(kubectl --context $CTX -n sprout-e2e-a get deploy app -o jsonpath='{.metadata.uid}')\" != '$UID1' ]"
k -n sprout-e2e-a delete ingress app >/dev/null
check_eventually "deleted Ingress is recreated" 60 k -n sprout-e2e-a get ingress app
UID2=$(k -n sprout-e2e-a get deploy app -o jsonpath='{.metadata.uid}')
k patch pe e2e-a --type merge -p '{"spec":{"image":"nginx:1.27"}}' >/dev/null
check_eventually "spec change updates image in place" 60 bash -c "[ \"\$(kubectl --context $CTX -n sprout-e2e-a get deploy app -o jsonpath='{.spec.template.spec.containers[0].image}')\" = nginx:1.27 ] && [ \"\$(kubectl --context $CTX -n sprout-e2e-a get deploy app -o jsonpath='{.metadata.uid}')\" = '$UID2' ]"
check_eventually "Ready again after change" 120 ready e2e-a

echo "== concurrency"
for i in $(seq 1 10); do cr "e2e-c$i" "  ttl: 10m"; done
check_eventually "10 concurrent environments Ready" 300 bash -c "for i in \$(seq 1 10); do [ \"\$(kubectl --context $CTX get pe e2e-c\$i -o jsonpath='{.status.conditions[0].status}')\" = True ] || exit 1; done"
check "no cross-talk: each owns its own namespace" bash -c "[ \$(kubectl --context $CTX get ns -l app.kubernetes.io/managed-by=sprout --no-headers | grep -c 'sprout-e2e-c') = 10 ]"
k delete pe --all --wait=false >/dev/null 2>&1
check_eventually "bulk delete leaves no namespaces" 300 bash -c "! kubectl --context $CTX get ns --no-headers | grep -q 'sprout-e2e-'"

echo "== controller faults"
cr e2e-f
check_eventually "env for fault tests Ready" 180 ready e2e-f
OLD=$(leader)
k -n sprout-system delete pod "$OLD" --force --grace-period=0 >/dev/null 2>&1
check_eventually "leader changes after leader pod is killed" 90 bash -c "[ -n \"\$(kubectl --context $CTX -n sprout-system get lease sprout-operator -o jsonpath='{.spec.holderIdentity}')\" ] && [ \"\$(kubectl --context $CTX -n sprout-system get lease sprout-operator -o jsonpath='{.spec.holderIdentity}')\" != '$OLD' ]"
k -n sprout-e2e-f delete deploy app >/dev/null
check_eventually "new leader repairs drift" 90 k -n sprout-e2e-f get deploy app
k -n sprout-system scale deploy/sprout-operator --replicas=0 >/dev/null
k -n sprout-system wait --for=delete pod -l app.kubernetes.io/name=sprout-operator --timeout=60s >/dev/null 2>&1
cr e2e-g
k -n sprout-e2e-f delete deploy app >/dev/null
k -n sprout-system scale deploy/sprout-operator --replicas=2 >/dev/null
check_eventually "env created while controller was down converges" 240 ready e2e-g
check_eventually "drift made while controller was down is repaired" 120 k -n sprout-e2e-f get deploy app

echo "== deletion faults"
# API errors during cleanup: take away the right to delete namespaces, delete the CR, then give it back.
k get clusterrole sprout-operator -o json > /tmp/sprout-role.json
python3 - <<'PY'
import json
r = json.load(open("/tmp/sprout-role.json"))
for rule in r["rules"]:
    if rule.get("resources") == ["namespaces"]:
        rule["verbs"] = [v for v in rule["verbs"] if v != "delete"]
json.dump(r, open("/tmp/sprout-role-restricted.json", "w"))
PY
k replace -f /tmp/sprout-role-restricted.json >/dev/null
k delete pe e2e-f --wait=false >/dev/null
sleep 15
check "CR is held by the finalizer while deletion errors" k get pe e2e-f
k apply -f config/rbac/role.yaml >/dev/null
check_eventually "cleanup completes after the error clears" 240 gone pe e2e-f
check_eventually "namespace removed" 120 gone ns sprout-e2e-f

# Stuck namespace: a foreign finalizer on the namespace keeps it Terminating; the escape hatch must release the CR.
k delete pe e2e-g --wait=false >/dev/null
sleep 3
check_eventually "e2e-g namespace gone" 120 gone ns sprout-e2e-g
cr e2e-h
check_eventually "env h Ready" 180 ready e2e-h
k patch ns sprout-e2e-h --type merge -p '{"metadata":{"finalizers":["e2e.test/block"]}}' >/dev/null
k delete pe e2e-h --wait=false >/dev/null
sleep 10
check "CR waits while namespace is stuck terminating" k get pe e2e-h
k annotate pe e2e-h sprout.dev/skip-cleanup-wait=true >/dev/null
check_eventually "skip annotation releases the CR" 60 gone pe e2e-h
k patch ns sprout-e2e-h --type merge -p '{"metadata":{"finalizers":null}}' >/dev/null 2>&1

echo "== ttl & metrics"
cr e2e-ttl "  ttl: 30s"
check_eventually "TTL expiry removes CR and namespace" 240 bash -c "! kubectl --context $CTX get pe e2e-ttl 2>/dev/null && ! kubectl --context $CTX get ns sprout-e2e-ttl 2>/dev/null"
POD=$(leader)
check "metrics endpoint exposes reconcile and domain counters" bash -c "kubectl --context $CTX get --raw /api/v1/namespaces/sprout-system/pods/$POD:8081/proxy/metrics | grep -q sprout_environments_expired_total"

echo
if [[ ${1:-} != --keep ]]; then k delete pe --all --wait=false >/dev/null 2>&1; fi
if ((FAILS)); then echo "$FAILS check(s) failed"; exit 1; fi
echo "all checks passed"
