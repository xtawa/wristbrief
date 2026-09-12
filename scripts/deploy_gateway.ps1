[CmdletBinding()]
param(
    [switch]$SkipInstall,
    [switch]$SkipLogin,
    [switch]$SkipMigrations,
    [switch]$SkipTypecheck,
    [string]$HealthUrl
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Step {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Resolve-Tool {
    param([Parameter(Mandatory)][string]$Name)
    $lookupName = if ($env:OS -eq "Windows_NT") { "$Name.cmd" } else { $Name }
    $command = Get-Command $lookupName -ErrorAction SilentlyContinue
    if (-not $command) {
        throw "Required command '$Name' was not found. Install Node.js/npm first."
    }
    return $command.Path
}

function Invoke-GatewayCommand {
    param(
        [Parameter(Mandatory)][string]$CommandPath,
        [Parameter()][string[]]$Arguments = @()
    )

    Push-Location -LiteralPath $gatewayRoot
    try {
        & $CommandPath @Arguments
        if ($LASTEXITCODE -ne 0) {
            $rendered = $Arguments -join " "
            throw "Command failed ($LASTEXITCODE): $CommandPath $rendered"
        }
    }
    finally {
        Pop-Location
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gatewayRoot = Join-Path $repoRoot "gateway"
$configPath = Join-Path $gatewayRoot "wrangler.toml"
$migrationsPath = Join-Path $gatewayRoot "migrations"

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Gateway Wrangler config was not found: $configPath"
}
if (-not (Test-Path -LiteralPath $migrationsPath -PathType Container)) {
    throw "Gateway migrations directory was not found: $migrationsPath"
}

$config = Get-Content -LiteralPath $configPath -Raw
if ($config -notmatch '(?m)^\s*name\s*=\s*"wristbrief-gateway"\s*$') {
    throw "wrangler.toml must deploy the wristbrief-gateway Worker."
}

$databaseIdMatch = [regex]::Match($config, '(?m)^\s*database_id\s*=\s*"([^"]+)"\s*$')
if (-not $databaseIdMatch.Success) {
    throw "wrangler.toml is missing a D1 database_id. Create the D1 database first."
}

$databaseId = $databaseIdMatch.Groups[1].Value.Trim()
if ([string]::IsNullOrWhiteSpace($databaseId) -or $databaseId -match 'wristbrief-account-db-id|placeholder|<|>') {
    throw "Replace the placeholder D1 database_id in gateway/wrangler.toml before deploying."
}

$npm = Resolve-Tool "npm"
$npx = Resolve-Tool "npx"

Write-Host "WristBrief Gateway deploy" -ForegroundColor Green
Write-Host "Repository: $repoRoot"
Write-Host "D1 database ID: $databaseId"

if (-not $SkipInstall) {
    Write-Step "Installing gateway dependencies"
    Invoke-GatewayCommand $npm @("ci")
}

if (-not $SkipTypecheck) {
    Write-Step "Running gateway typecheck"
    Invoke-GatewayCommand $npm @("run", "typecheck")
}

if (-not $SkipLogin) {
    Write-Step "Checking Wrangler authentication"
    Push-Location -LiteralPath $gatewayRoot
    try {
        & $npx "wrangler" "whoami"
        $whoamiExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    if ($whoamiExit -ne 0) {
        Write-Host "Wrangler is not authenticated; opening the Cloudflare browser login..." -ForegroundColor Yellow
        Invoke-GatewayCommand $npx @("wrangler", "login")
    }
}

if (-not $SkipMigrations) {
    Write-Step "Applying pending remote D1 migrations"
    Write-Host "Wrangler will ask for confirmation before changing the remote database." -ForegroundColor Yellow
    Invoke-GatewayCommand $npx @(
        "wrangler",
        "d1",
        "migrations",
        "apply",
        "wristbrief-account-db",
        "--remote",
        "--config",
        "wrangler.toml"
    )
}
else {
    Write-Warning "D1 migrations were skipped. Deploy only if the remote schema is already current."
}

Write-Step "Deploying Worker to Cloudflare"
Invoke-GatewayCommand $npx @("wrangler", "deploy", "--config", "wrangler.toml")

if (-not [string]::IsNullOrWhiteSpace($HealthUrl)) {
    $healthTarget = $HealthUrl.Trim().TrimEnd("/")
    if (-not $healthTarget.EndsWith("/health", [System.StringComparison]::OrdinalIgnoreCase)) {
        $healthTarget = "$healthTarget/health"
    }

    Write-Step "Checking $healthTarget"
    try {
        $response = Invoke-WebRequest -Uri $healthTarget -Method Get -UseBasicParsing -TimeoutSec 30
        if ($response.StatusCode -ne 200) {
            throw "Expected HTTP 200, received HTTP $($response.StatusCode)."
        }

        $payload = $response.Content | ConvertFrom-Json
        if (-not $payload.ok) {
            throw "Health endpoint did not return { ok: true }."
        }

        Write-Host "Health check passed: HTTP $($response.StatusCode)" -ForegroundColor Green
    }
    catch {
        throw "Health check failed: $($_.Exception.Message)"
    }
}
else {
    Write-Host "Deployment completed. Pass -HealthUrl https://<domain> to run /health automatically." -ForegroundColor Green
}

Write-Host "`nNote: the repository currently declares a Queue producer but does not include a transcript Queue consumer." -ForegroundColor Yellow
