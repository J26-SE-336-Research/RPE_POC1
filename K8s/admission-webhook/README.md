# RRPE Pod admission webhook

This webhook injects the existing Netty proxy and its `iptables` setup init
container when a Pod template opts in. It uses the same annotations as the
manual deployments. The webhook runs only for Pod creation in namespaces labeled
`resilience.rrpe.dev/injection=enabled`; individual Pods opt in with
`resilience.rrpe.dev/inject: "enabled"`.

## Requirements

- Docker and `kubectl`.
- The Netty proxy image configured below must be pullable by the cluster nodes.

The API server verifies the webhook's HTTPS certificate. For this PoC, the
included certificate generator creates a development CA and service certificate;
the setup script stores the TLS Secret and patches the CA bundle. Production
clusters should use their certificate manager or platform PKI instead.

## Install on Minikube

Run these commands from the repository root in PowerShell.

```powershell
docker build -t rrpe-admission-webhook:dev -f admission-webhook/Dockerfile admission-webhook
minikube image load rrpe-admission-webhook:dev

kubectl apply -f K8s/admission-webhook/namespace.yaml
./K8s/admission-webhook/setup-tls.ps1

kubectl apply -f K8s/admission-webhook/webhook.yaml
./K8s/admission-webhook/setup-tls.ps1 -PatchCA
kubectl rollout status deployment/rrpe-admission-webhook -n rrpe-system --timeout=120s
kubectl get mutatingwebhookconfiguration rrpe-pod-injector -o yaml
```

Before enabling injection, confirm the webhook Deployment is available and
`clientConfig.caBundle` in `rrpe-pod-injector` is populated. Then enable the
namespace selector:

```powershell
kubectl label namespace default resilience.rrpe.dev/injection=enabled --overwrite
```

The webhook fails closed inside labeled namespaces. Keep the namespace label
off until the webhook is ready, because the API server will reject Pod creation
there if it cannot reach the webhook.
The generated service certificate is valid for 90 days; rerun the setup script
and restart the webhook Deployment before it expires. The generated key files
are ignored by Git under `K8s/admission-webhook/tls/`.

## Opt a workload in

The Pod template must have these annotations:

```yaml
resilience.rrpe.dev/inject: "enabled"
resilience.rrpe.dev/role: "service" # use "gateway" for the API gateway
resilience.rrpe.dev/inbound-ports: "8081"
resilience.rrpe.dev/outbound-ports: "8082:8084"
resilience.rrpe.dev/excluded-outbound-ports: ""
resilience.rrpe.dev/proxy-port: "8080"
```

The role, inbound port, and proxy port are required. Outbound and exclusion lists may
be empty; port lists accept comma-separated ports and ranges, with at most 15
entries per list. `default-deadline-ms` is optional and defaults to 5000 ms for
the gateway role. For the API gateway, set role to `gateway`, use its tested
proxy port `15001`, and set `default-deadline-ms` to the desired gateway
deadline (the current manual deployment uses `1000`).

**Migration from the manual setup:** remove the manually declared
`configure-proxy-routing` init container and `netty-proxy` sidecar from the
workload before adding the opt-in annotation. The webhook rejects opted-in Pods
that already contain those names, preventing duplicate interception rules.

For the currently deployed Order service, the included strategic-merge patch
and four companion patches migrate the demo workloads by removing their
manually declared proxy containers and opting in:

```powershell
kubectl patch deployment order-deployment --type strategic --patch-file K8s/admission-webhook/order-opt-in-patch.yaml
kubectl patch deployment apigateway-deployment --type strategic --patch-file K8s/admission-webhook/api-gateway-opt-in-patch.yaml
kubectl patch deployment inventory-service-deployment --type strategic --patch-file K8s/admission-webhook/inventory-opt-in-patch.yaml
kubectl patch deployment payment-deployment --type strategic --patch-file K8s/admission-webhook/payment-opt-in-patch.yaml
kubectl patch deployment notification-service-deployment --type strategic --patch-file K8s/admission-webhook/notification-opt-in-patch.yaml

kubectl rollout status deployment/order-deployment --timeout=180s
kubectl rollout status deployment/apigateway-deployment --timeout=180s
kubectl rollout status deployment/inventory-service-deployment --timeout=180s
kubectl rollout status deployment/payment-deployment --timeout=180s
kubectl rollout status deployment/notification-service-deployment --timeout=180s
```

## Verify the injected Pod

```powershell
kubectl get pods -l app=order
kubectl get pods -l app=order -o jsonpath="{.items[0].spec.initContainers[*].name}"
kubectl get pods -l app=order -o jsonpath="{.items[0].spec.containers[*].name}"
kubectl logs deployment/order-deployment -c configure-proxy-routing
kubectl logs deployment/order-deployment -c netty-proxy
kubectl get pods -o json | ConvertFrom-Json | ForEach-Object { $_.items | ForEach-Object { "$($_.metadata.name): injected=$($_.metadata.annotations.'resilience.rrpe.dev/injected') containers=$($_.spec.containers.name -join ',')" } }
```

The init-container and container output should each list one injected component.
Then run the existing gateway request check and correlate its request ID in the
proxy logs, as with the manual sidecar deployment.

## Configuration

The webhook Deployment sets `PROXY_IMAGE`, `REQUEST_CONTEXT_TTL_MILLIS`, and
`DEFAULT_DEADLINE_MILLIS`. Change those values in `webhook.yaml` for your image
registry or policy defaults. `failurePolicy: Fail` makes injection fail closed
for Pod creation in opted-in namespaces; remove the namespace label to disable
the webhook for a namespace during recovery.

The injected routing init container needs `NET_ADMIN`; the proxy runs as UID 0
with `NET_RAW` for transparent proxy sockets. Namespaces enforcing Kubernetes
Pod Security `restricted` will reject this configuration unless an appropriate
policy exception is configured.
