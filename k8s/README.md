# Running this on Kubernetes (Minikube)

Nine manifests, applied in order. Each stage below has a command to
verify it worked before you move to the next — don't apply everything
at once and hope; check as you go.

Commands are PowerShell (Windows). If you're on macOS/Linux, drop the
backtick line-continuations and use `\` instead, and swap
`Invoke-Expression` for `eval` where noted.

## 0. Prerequisites

- Minikube and kubectl installed
- Docker Desktop running
- The `rrpe-ecommerce-poc` project already builds successfully with
  `docker compose up --build` — confirm that first. Kubernetes adds a
  layer on top; it doesn't fix an image that doesn't build.

## 1. Start Minikube

```powershell
minikube start --driver=docker --cpus=4 --memory=6144
```

Check it's actually up before continuing:

```powershell
minikube status
kubectl get nodes
```

You should see one node in `Ready` state.

## 2. Point your Docker CLI at Minikube's Docker daemon

This is the step that avoids `ErrImageNeverPull` — the images need to
be built *inside* Minikube's own Docker daemon, not your host's, or
Kubernetes won't be able to find them by name.

```powershell
minikube docker-env | Invoke-Expression
```

This only affects your current PowerShell session. If you open a new
terminal later, you'll need to run it again before rebuilding images.

Verify it actually switched:

```powershell
docker info --format '{{.Name}}'
# should print something like "minikube", not your host machine's name
```

## 3. Build all five images

From the project root (`rrpe-ecommerce-poc/`), with the Minikube
docker-env still active in this shell:

```powershell
docker build -t rrpe/api-gateway:latest ./api-gateway
docker build -t rrpe/order-service:latest ./order-service
docker build -t rrpe/inventory-service:latest ./inventory-service
docker build -t rrpe/payment-service:latest ./payment-service
docker build -t rrpe/notification-service:latest ./notification-service
```

Confirm all five exist inside Minikube's daemon:

```powershell
docker images | Select-String "rrpe/"
```

You should see exactly five images. If you rebuild a service later
after code changes, just re-run its one `docker build` line — you
don't need to rebuild the others.

## 4. Apply the manifests, in stages

**Stage 1 — namespace, credentials, config:**

```powershell
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/01-secret.yaml
kubectl apply -f k8s/02-configmap.yaml
```

```powershell
kubectl get all -n rrpe-ecommerce
# expect: "No resources found" — that's correct, nothing runs yet
```

**Stage 2 — Postgres:**

```powershell
kubectl apply -f k8s/03-postgres.yaml
```

Wait for it to actually be ready before moving on — don't just check
it exists, check it's healthy:

```powershell
kubectl get pods -n rrpe-ecommerce -w
# wait until postgres-xxxxx shows 1/1 Running, then Ctrl+C
```

If it stays `Pending`, check the PVC:

```powershell
kubectl get pvc -n rrpe-ecommerce
kubectl describe pod -n rrpe-ecommerce -l app=postgres
```

**Stage 3 — the four business services:**

```powershell
kubectl apply -f k8s/04-inventory-service.yaml
kubectl apply -f k8s/05-payment-service.yaml
kubectl apply -f k8s/06-notification-service.yaml
kubectl apply -f k8s/07-order-service.yaml
```

Watch them come up — inventory/payment/order-service each run a
`wait-for-postgres` initContainer first, so they'll sit in `Init:0/1`
for a few seconds before their main container even starts:

```powershell
kubectl get pods -n rrpe-ecommerce -w
```

If a pod stays in `Init:0/1` for more than ~30 seconds, check why:

```powershell
kubectl logs -n rrpe-ecommerce <pod-name> -c wait-for-postgres
```

If a pod reaches `Running` but then goes to `CrashLoopBackOff`, check
the actual application log, not the init container:

```powershell
kubectl logs -n rrpe-ecommerce <pod-name>
```

**Stage 4 — the gateway:**

```powershell
kubectl apply -f k8s/08-api-gateway.yaml
kubectl get pods -n rrpe-ecommerce
```

All five deployments plus postgres should now show `Running` with
`1/1` ready.

## 5. Reach the gateway from outside the cluster

```powershell
minikube service api-gateway -n rrpe-ecommerce --url
```

This prints a URL — use it exactly as printed for every request from
the walkthrough in the main README, e.g.:

```powershell
$GW = minikube service api-gateway -n rrpe-ecommerce --url
curl "$GW/api/inventory/products"
```

## 6. Confirm it actually works end to end

Same walkthrough as the docker-compose README, just against `$GW`
instead of `localhost:8080`. If the successful-order and
forced-payment-failure scenarios both behave the same way they did
under docker-compose, the Kubernetes migration is verified, not just
"the pods are green."

## Cleaning up

```powershell
kubectl delete namespace rrpe-ecommerce
# removes everything in one shot, including the PVC's claim (the
# underlying data is gone once the PVC is deleted)

minikube stop
```

## What changed vs. docker-compose, and what didn't

| | docker-compose | Kubernetes |
|---|---|---|
| Service discovery | container/service name via Docker's embedded DNS | Service name via cluster DNS — **same names, same URLs, no code changes** |
| Startup ordering | `depends_on: condition: service_healthy` | No native equivalent — replaced with `initContainers` that wait on `pg_isready` |
| Secrets | `.env` file, plain text on disk | `Secret` object — base64-encoded, not encrypted by default; still the correct native primitive to use |
| Config | `.env` file | `ConfigMap` for non-secret values, `Secret` for credentials |
| Persistent data | named Docker volume | `PersistentVolumeClaim` |
| External access | published ports | `NodePort` Service (or `minikube service` for a one-off tunnel) |

The one thing worth sitting with: almost nothing about the
*application* changed. Every environment variable your Spring Boot
services read is exactly the same name and, for the inter-service
URLs, exactly the same value. That's a direct payoff of building this
PoC around plain HTTP calls to fixed addresses in the first place —
the "why this shape" note in the main README wasn't just tidiness, it's
why this migration was mechanical rather than a rewrite.
