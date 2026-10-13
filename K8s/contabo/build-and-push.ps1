param(
    [string]$Tag = 'contabo-20261013'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$images = @(
    @{ Name = 'rrpe-api-gateway'; Context = 'api-gateway'; Dockerfile = 'api-gateway/Dockerfile' },
    @{ Name = 'rrpe-order-service'; Context = 'order-service'; Dockerfile = 'order-service/Dockerfile' },
    @{ Name = 'rrpe-inventory-service'; Context = 'inventory-service'; Dockerfile = 'inventory-service/Dockerfile' },
    @{ Name = 'rrpe-payment-service'; Context = 'payment-service'; Dockerfile = 'payment-service/Dockerfile' },
    @{ Name = 'rrpe-notification-service'; Context = 'notification-service'; Dockerfile = 'notification-service/Dockerfile' },
    @{ Name = 'rrpe-frontend'; Context = 'frontend'; Dockerfile = 'frontend/Dockerfile' },
    @{ Name = 'rrpe-netty-sidecar-proxy'; Context = 'netty-sidecar-proxy'; Dockerfile = 'netty-sidecar-proxy/docker/Dockerfile' },
    @{ Name = 'rrpe-admission-webhook'; Context = 'admission-webhook'; Dockerfile = 'admission-webhook/Dockerfile' }
)

foreach ($image in $images) {
    $taggedImage = "rameshkalhara/$($image.Name):$Tag"
    Write-Host "Building $taggedImage"
    & docker build --platform linux/amd64 -t $taggedImage -f (Join-Path $repositoryRoot $image.Dockerfile) (Join-Path $repositoryRoot $image.Context)
    if ($LASTEXITCODE -ne 0) { throw "Build failed for $taggedImage" }
    Write-Host "Pushing $taggedImage"
    & docker push $taggedImage
    if ($LASTEXITCODE -ne 0) { throw "Push failed for $taggedImage" }
}

Write-Host "All images built and pushed with tag $Tag."
