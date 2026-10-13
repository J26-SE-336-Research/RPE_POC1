# Component 2 metrics in Minikube with Prometheus

This guide continues from the working state where the `minikube` node and the application/PostgreSQL pods are `Running`. It installs a small Prometheus Operator stack, scrapes the five Netty sidecars using the existing `Netty_Proxy_PodMonitor.yaml`, and shows how to verify Component 2 metrics.

## What this collects

Each service/gateway pod has a `netty-proxy` sidecar exposing `http://<pod>:9090/metrics`. The existing PodMonitor selects pods labelled `app=apigateway`, `order`, `inventory`, `payment`, or `notification` in namespace `default`, and scrapes the named `metrics` port every 15 seconds. The PodMonitor is labelled `release: monitoring`; therefore install the Helm release with that exact name.

Component 2 provides request counts, in-flight requests, successful/failed requests, deadline detections, cancellations, service latency histograms, and gateway chain/deadline-budget metrics. It does not currently expose original-request, retry-attempt, successful-retry, or rejection counters; do not present those as collected metrics. Per-container CPU/memory are Kubernetes/cAdvisor metrics, not Netty metrics.

## 1. Install Helm on Windows

In PowerShell, install Helm with WinGet, then close and reopen PowerShell so the updated PATH is loaded:

```powershell
winget install --id Helm.Helm --exact
```

Verify Helm and ensure kubectl points at Minikube:

```powershell
helm version
minikube status
minikube kubectl -- config current-context
minikube kubectl -- get nodes
```

The node should be `minikube` and `Ready`. Use `minikube kubectl --` for cluster commands throughout this guide.

## 2. Install Prometheus Operator using the small local profile

From the project root (`C:\Projects\RPE_POC1`), add the official Prometheus Community chart repository and install the stack in the `monitoring` namespace:

```powershell
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack `
  --namespace monitoring `
  --create-namespace `
  --values K8s/monitoring-values.yaml `
  --wait `
  --timeout 10m
```

This profile keeps Prometheus and the Operator/CRDs needed for `PodMonitor`, and disables Grafana, Alertmanager, kube-state-metrics, and default alert rules to reduce resource use on the 3.5 GB Minikube node. The chart also installs one small node-exporter pod. Prometheus itself has a 256 MiB request and 512 MiB limit. Helm must be able to reach chart/image registries; initial image downloads may take several minutes.

Check installation:

```powershell
helm list -n monitoring
minikube kubectl -- get pods -n monitoring
minikube kubectl -- get crd podmonitors.monitoring.coreos.com
```

Wait until the Prometheus and Operator pods are `Running` (Prometheus may be `2/2` because of its config reloader).

## 3. Register the Component 2 sidecars

Apply the existing monitor:

```powershell
minikube kubectl -- apply -f K8s/Netty_Proxy_PodMonitor.yaml
minikube kubectl -- get podmonitor -n monitoring
```

No application redeployment is needed. Prometheus discovers the five pod targets through the PodMonitor. Allow at least 30 seconds for two scrape intervals.

## 4. Open Prometheus

In a separate PowerShell terminal, start a port-forward and leave that terminal open:

```powershell
minikube kubectl -- port-forward -n monitoring svc/monitoring-kube-prometheus-prometheus 9090:9090
```

Open `http://localhost:9090` in a browser and choose **Status > Targets**. Search for the `netty-proxies` target group. Targets should show `UP` for the `default` namespace pods. The Prometheus service name above is the standard name for this Helm release; if Kubernetes reports it missing, inspect the actual service with:

```powershell
minikube kubectl -- get services -n monitoring
```

## 5. Generate application requests

In another terminal, start the gateway URL helper and leave it running:

```powershell
minikube service api-gateway-service --url
```

Copy the printed URL (for example, `http://127.0.0.1:xxxxx`) and use it below. This helper may need to remain running with the Docker driver on Windows.

Generate inventory traffic (replace the URL with the one printed above):

```powershell
$gateway = 'http://127.0.0.1:xxxxx'
Invoke-RestMethod -Uri "$gateway/api/inventory/products" -Method Get
```

To exercise order plus downstream services, submit a sample order:

```powershell
$body = @{
  customerId = 'prometheus-demo'
  items = @(@{ sku = 'SKU-KEYBOARD-01'; quantity = 1 })
  forcePaymentFail = $false
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Uri "$gateway/api/orders" -Method Post `
  -ContentType 'application/json' `
  -Body $body
```

Use a valid SKU from the inventory response. An order may fail if the database has not seeded that SKU or if application startup is still initializing; the GET inventory request is enough to verify gateway/inventory proxy metrics.

## 6. Query Component 2 metrics

Run these in the Prometheus expression box (`http://localhost:9090/graph`). Metrics with `_total` are counters: use `rate()` or `increase()` to interpret changes over time.

**Are sidecar metrics targets being scraped?**

```promql
up{namespace="default", endpoint="metrics"}
```

Expect one `1` for each scraped sidecar pod. A `0` indicates a failing scrape; no result usually means the PodMonitor was not selected or no scrape has occurred yet.

**Requests per second by service:**

```promql
sum by (service) (rate(sidecar_route_requests_total[5m]))
```

**Observed inbound requests over five minutes by service and route:**

```promql
sum by (service, method, route) (increase(sidecar_route_requests_total[5m]))
```

**Proxy-observed service p95 latency:**

```promql
1000 * histogram_quantile(
  0.95,
  sum by (service, le) (rate(sidecar_service_request_duration_seconds_bucket[5m]))
)
```

The result is milliseconds. This histogram measures proxy-observed inbound request duration, not every application operation.

**Proxy failures per second by service:**

```promql
sum by (service) (rate(sidecar_route_requests_failed_total[5m]))
```

This counter increments for HTTP 429 and 5xx outcomes recorded by the sidecar.

**Current in-flight requests:**

```promql
sum by (service) (sidecar_route_requests_in_flight)
```

**Gateway chain deadline exceedances over five minutes:**

```promql
sum by (service, method, route, deadline_ms) (
  increase(sidecar_gateway_request_chains_deadline_exceeded_total[5m])
)
```

**Configured gateway deadline budgets:**

```promql
sidecar_gateway_deadline_budget_milliseconds
```

The budget gauge is emitted by the gateway proxy and appears after at least one successful scrape. Route labels are normalized/configured routes; request IDs and raw query values are not Prometheus labels.

## 7. Intelligence-layer connection (optional)

Prometheus scraping works independently of the intelligence layer. The current `K8s/09-intelligence-layer.yaml` sets `PROMETHEUS_COLLECTION_ENABLED` to `false`, so applying it does not enable live collection. If you deploy that component later, its Prometheus URL must use the in-cluster Prometheus Service DNS, not `localhost`:

```text
http://monitoring-kube-prometheus-prometheus.monitoring.svc.cluster.local:9090
```

Set `PROMETHEUS_COLLECTION_ENABLED` to `true` only when that deployment is intended to query live Prometheus data. Confirm the metrics API connection check and fresh telemetry before interpreting its adaptive decisions. The intelligence layer currently documents missing retry/rejection counters; its deadline-health path is designed to work with the existing counters and latency metrics.

## 8. Troubleshooting checklist

- **Helm says it is not recognized:** reopen PowerShell after the WinGet install, then run `helm version`.
- **PodMonitor kind is unknown:** wait for the Helm install and verify the `podmonitors.monitoring.coreos.com` CRD exists.
- **No `netty-proxies` targets:** check the PodMonitor namespace/labels and confirm all app pods are in `default`:

  ```powershell
  minikube kubectl -- get podmonitor -n monitoring --show-labels
  minikube kubectl -- get pods -n default --show-labels
  ```

- **Target `DOWN`:** inspect target error in Prometheus **Status > Targets**, then confirm the sidecar listens on port `metrics`/9090 and `/metrics`:

  ```powershell
  minikube kubectl -- logs -n default deploy/order-deployment -c netty-proxy
  ```

- **Metrics exist but counters are zero or absent:** generate a request through the gateway and wait 15-30 seconds. Most route counters/histograms are created when a request is observed; the configured deadline gauge is emitted by the gateway sidecar.
- **Prometheus is Pending or OOMKilled:** inspect `minikube kubectl -- describe pod -n monitoring <pod-name>` and node memory. This laptop's Minikube allocation is tight for the application plus monitoring. Keep Grafana and Alertmanager disabled; reduce other workloads or raise the Docker/Minikube memory allocation if capacity permits.

## 9. Stop and remove the monitoring stack

Stop the port-forward with `Ctrl+C`. To remove Prometheus components while keeping the application and Minikube cluster:

```powershell
helm uninstall monitoring -n monitoring
```

The chart's CRDs are cluster-scoped and may remain after uninstall. Do not delete CRDs for routine cleanup; they are harmless and removing them also removes associated custom monitoring resources.
