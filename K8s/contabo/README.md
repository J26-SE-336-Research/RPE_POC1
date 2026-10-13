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
- A Docker Hub account able to push images under `rameshkalhara`.
- The Contabo VPS SSH password and public IP, used only over SSH.
- A teammate with Contabo panel access to allow inbound TCP 80.

Never commit registry credentials, database passwords, kubeconfig files, TLS
private keys, or K3s tokens. Do not paste them into chat.

## Confirm and build images

Sign in to Docker Hub with a personal access token (PAT) that has Repo Read &
Write permission. A PAT is a password-equivalent; do not put it in a command,
script, source file, or chat. Docker PAT permissions apply to every repository
the account can access, so choose the lowest suitable permission and revoke the
PAT when it is no longer needed.

From the repository root in PowerShell, build and push the Linux/amd64 images:

```powershell
docker login --username rameshkalhara
.\K8s\contabo\build-and-push.ps1
```

The script publishes version tag `contabo-20261013` for the API gateway, four
services, frontend, Netty proxy, and admission webhook. The deployment manifests
already reference those exact tags. Rebuild with a new tag when source changes,
then update the tag in the manifests before deploying.

After pushing, make these repositories public so the VPS can pull them without
credentials, or create a Kubernetes image-pull Secret with a read-only token if
the repositories are private.

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

After replacing all image placeholders with the tags or digests built from the
current checkout:

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
