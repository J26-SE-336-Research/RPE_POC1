param(
    [switch]$PatchCA
)

$ErrorActionPreference = 'Stop'
$tlsDirectory = Join-Path $PSScriptRoot 'tls'

if ($PatchCA) {
    $caPath = Join-Path $tlsDirectory 'ca.crt'
    if (-not (Test-Path -LiteralPath $caPath)) {
        throw "CA certificate not found at $caPath. Run this script once without -PatchCA first."
    }

    $caBundle = [Convert]::ToBase64String([IO.File]::ReadAllBytes($caPath))
    $patchDocument = @(
        @{
            op    = 'replace'
            path  = '/webhooks/0/clientConfig/caBundle'
            value = $caBundle
        }
    )
    $patchJson = ConvertTo-Json -InputObject $patchDocument -Compress -Depth 5
    & kubectl patch mutatingwebhookconfiguration rrpe-pod-injector --type=json --patch $patchJson
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to patch the webhook CA bundle.'
    }
    Write-Host 'Webhook caBundle updated from the generated development CA.'
    exit 0
}

New-Item -ItemType Directory -Force -Path $tlsDirectory | Out-Null
$volume = "${tlsDirectory}:/tls"
& docker run --rm --user 0:0 --entrypoint /rrpe-admission-certgen -v $volume rrpe-admission-webhook:dev /tls
if ($LASTEXITCODE -ne 0) {
    throw 'Certificate generation failed. Build the webhook image first.'
}

$certificatePath = Join-Path $tlsDirectory 'tls.crt'
$keyPath = Join-Path $tlsDirectory 'tls.key'
$secretYaml = & kubectl create secret tls rrpe-admission-webhook-tls -n rrpe-system --cert=$certificatePath --key=$keyPath --dry-run=client -o yaml
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to create the webhook TLS Secret manifest.'
}
$secretYaml | & kubectl apply -f -
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to apply the webhook TLS Secret.'
}
Write-Host 'Development TLS Secret created. Apply webhook.yaml, then run this script again with -PatchCA.'
