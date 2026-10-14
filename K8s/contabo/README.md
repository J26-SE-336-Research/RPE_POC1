# Deploy RRPE E-Commerce to the Contabo K3s server

This bundle deploys the frontend, internal API gateway, four application services,
and PostgreSQL with persistent storage. The frontend is published through the
K3s-packaged Traefik ingress on HTTP port `80`, so visit `http://<server-ip>` and
allow inbound TCP `80` in the Contabo network firewall. The frontend Service is
cluster-internal; the API gateway and database are also cluster-internal.
This is a single-node demo and does not provide high availability or off-server
database backups.

## Requirements

- Docker Desktop on the build computer, with the Docker daemon running.
- `kubectl` installed on the build computer.
- The Contabo VPS SSH password and public IP, used only over SSH.
- A teammate with Contabo panel access to allow inbound TCP 80.

Never commit registry credentials, database passwords, kubeconfig files, TLS
private keys, or K3s tokens. Do not paste them into chat.

## Build and export images

This deployment imports images directly into the single K3s node, so Docker Hub
login and push access are not required. From the repository root in PowerShell,
build all eight Linux/amd64 images:

```powershell
.\K8s\contabo\build-local-images.ps1
```

Save those tagged images into one archive:

```powershell
$imageArchive = Join-Path $env:TEMP 'rrpe-ecommerce-images.tar'
$images = @(
  'rameshkalhara/rrpe-api-gateway:contabo-20261013',
  'rameshkalhara/rrpe-order-service:contabo-20261013',
  'rameshkalhara/rrpe-inventory-service:contabo-20261013',
  'rameshkalhara/rrpe-payment-service:contabo-20261013',
  'rameshkalhara/rrpe-notification-service:contabo-20261013',
  'rameshkalhara/rrpe-frontend:contabo-20261013',
  'rameshkalhara/rrpe-netty-sidecar-proxy:contabo-20261013',
  'rameshkalhara/rrpe-admission-webhook:contabo-20261013'
)
docker save --output $imageArchive $images
Write-Host "Image archive: $imageArchive"
```

Use Termius SFTP to copy `rrpe-ecommerce-images.tar` to `/root/` on the VPS.
Then, in the Termius shell, import and verify the images:

```bash
k3s ctr -n k8s.io images import /root/rrpe-ecommerce-images.tar
k3s ctr -n k8s.io images list | grep rameshkalhara
```

The manifests use `imagePullPolicy: Never`; K3s will run these preloaded images
without contacting Docker Hub. If source changes, rebuild and export again with
a new tag, then update the manifest image references to match.

## Prepare the server

Run commands from the Termius root shell. Use SSH/SFTP to transfer this folder
to `/root/rrpe-ecommerce-poc/K8s/contabo` on the VPS. Do not copy `.env`, TLS
files, kubeconfig, or registry credentials into the deployment folder.

Create the secret interactively; enter a new strong password at the prompt,
and keep it in your team password manager:

```bash
kubectl create namespace rrpe-ecommerce
read -rsp 'Database password: ' DB_PASSWORD; echo
kubectl -n rrpe-ecommerce create secret generic postgres-credentials \
  --from-literal=username=rrpe_admin \
  --from-literal=password="$DB_PASSWORD"
unset DB_PASSWORD
```

Before deployment, inspect the cluster for existing workloads and check storage:

```bash
kubectl get pods -A
kubectl get storageclass
```

If `local-path` is the default StorageClass and the `rrpe-ecommerce` namespace is
unused, proceed. If there are existing workloads or no default StorageClass,
stop and review before continuing.

## Deploy

After the image import succeeds:

```bash
kubectl apply -n rrpe-ecommerce -f 01-config.yaml
kubectl apply -n rrpe-ecommerce -f 02-postgres-init.yaml
kubectl apply -n rrpe-ecommerce -f 03-postgres.yaml
kubectl rollout status deployment/postgres -n rrpe-ecommerce --timeout=180s
kubectl apply -n rrpe-ecommerce -f 04-backend.yaml
```

Generate a fresh webhook CA and service certificate, create the
`rrpe-admission-webhook-tls` Secret in `rrpe-system`, and patch `caBundle` in
`06-webhook.yaml` before applying it. Keep the private keys outside Git with
permissions restricted to your administrator account.

Apply `webhook.yaml` only after its image and TLS Secret exist. Wait until the
webhook Deployment is Ready and `caBundle` is populated, then label the app
namespace and apply the frontend:

```bash
kubectl label namespace rrpe-ecommerce resilience.rrpe.dev/injection=enabled --overwrite
kubectl apply -n rrpe-ecommerce -f 05-frontend.yaml
kubectl get pods -n rrpe-ecommerce -w
```

Open inbound TCP 80 in the Contabo panel, then visit `http://<server-ip>`.
The gateway, microservices, and Postgres do not need public ports. Do not open
UDP 8472 to the internet; this is a single-node cluster and VXLAN traffic is
internal to the node.

## Verify

```bash
kubectl get pods,services,pvc -n rrpe-ecommerce
kubectl logs deployment/order -n rrpe-ecommerce
```

PostgreSQL initialization scripts run only when its data directory is empty.
Changing the Secret later does not rotate the password inside an existing
database. Back up the PVC data off-server before relying on it.
