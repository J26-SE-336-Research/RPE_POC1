# Pod container CPU and memory metrics

`Kubelet_Cadvisor_ServiceMonitor.yaml` asks the Prometheus Operator to scrape the kubelet cAdvisor endpoint for container resource metrics. It keeps only the CPU usage counter and memory working-set gauge to limit ingestion. Prometheus needs permission to read kubelet metrics (`nodes/metrics`), and the cluster must expose the kubelet HTTPS metrics service in `kube-system` with the `k8s-app: kubelet` label.

Before applying it, check Prometheus targets or query `container_cpu_usage_seconds_total`. Some Prometheus distributions already scrape kubelet cAdvisor by default. If that metric is already present, keep the existing scrape and do not apply this extra monitor, or Prometheus may ingest duplicates.

If cAdvisor metrics are not already being scraped, apply the monitor after the Prometheus Operator and its CRDs are installed:

```bash
kubectl apply -f K8s/Kubelet_Cadvisor_ServiceMonitor.yaml
```

Example PromQL for the `default` namespace:

```promql
# CPU usage in cores, averaged over five minutes
sum by (namespace, pod, container) (
  rate(container_cpu_usage_seconds_total{
    namespace="default", container!="", container!="POD"
  }[5m])
)
```

```promql
# Current memory working set in bytes
sum by (namespace, pod, container) (
  container_memory_working_set_bytes{
    namespace="default", container!="", container!="POD"
  }
)
```

These are actual per-container usage measurements. Join with Kubernetes pod-label metrics such as `kube_pod_labels` when queries need the service's `app` label. This monitor adds cluster resource collection only; it does not change the intelligence service.
