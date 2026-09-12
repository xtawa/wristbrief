[CmdletBinding()]
param(
    [switch]$SkipInstall,
    [switch]$SkipLogin,
    [switch]$SkipMigrations,
    [switch]$SkipSecrets,
    [switch]$SkipProvisioning,
    [switch]$SkipTypecheck,
    [switch]$InteractiveMigrations,
    [switch]$DryRun,
    [string]$SecretsFile,
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

function Get-ConfigValue {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Key
    )

    $pattern = '(?m)^\s*' + [regex]::Escape($Key) + '\s*=\s*"([^"]*)"\s*$'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        return $null
    }
    return $match.Groups[1].Value.Trim()
}

function Invoke-GatewayCommand {
    param(
        [Parameter(Mandatory)][string]$CommandPath,
        [Parameter()][string[]]$Arguments = @(),
        [Parameter()][hashtable]$EnvironmentOverrides = @{}
    )

    $previousEnvironment = @{}
    Push-Location -LiteralPath $gatewayRoot
    try {
        foreach ($entry in $EnvironmentOverrides.GetEnumerator()) {
            $name = [string]$entry.Key
            $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
            [Environment]::SetEnvironmentVariable($name, [string]$entry.Value, "Process")
        }

        & $CommandPath @Arguments
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            $rendered = $Arguments -join " "
            throw "Command failed ($exitCode): $CommandPath $rendered"
        }
    }
    finally {
        foreach ($entry in $previousEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        }
        Pop-Location
    }
}

function Invoke-RemoteMigrations {
    param(
        [Parameter(Mandatory)][string]$NpxPath,
        [Parameter(Mandatory)][bool]$PromptBeforeApply
    )

    Write-Step "Applying pending remote D1 migrations"
    $migrationArguments = @(
        "wrangler",
        "d1",
        "migrations",
        "apply",
        "ACCOUNT_DB",
        "--remote",
        "--config",
        "wrangler.toml"
    )
    if ($PromptBeforeApply) {
        Write-Host "Wrangler will ask for confirmation before changing the remote database." -ForegroundColor Yellow
        Invoke-GatewayCommand $NpxPath $migrationArguments
    }
    else {
        Write-Host "Automatic migration confirmation is enabled (CI=1). Use -InteractiveMigrations to prompt." -ForegroundColor Yellow
        Invoke-GatewayCommand $NpxPath $migrationArguments @{ CI = "1" }
    }
}

function Resolve-SecretsFile {
    param([string]$RequestedPath)

    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        $candidate = if ([IO.Path]::IsPathRooted($RequestedPath)) {
            $RequestedPath
        }
        else {
            Join-Path (Get-Location) $RequestedPath
        }

        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "Secrets file was not found: $candidate"
        }
        return (Resolve-Path -LiteralPath $candidate).Path
    }

    $defaultPath = Join-Path $gatewayRoot ".env.cloudflare.local"
    if (Test-Path -LiteralPath $defaultPath -PathType Leaf) {
        return (Resolve-Path -LiteralPath $defaultPath).Path
    }
    return $null
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
$workerName = Get-ConfigValue $config "name"
if ($workerName -ne "wristbrief-gateway") {
    throw "wrangler.toml must deploy the wristbrief-gateway Worker."
}

$databaseName = Get-ConfigValue $config "database_name"
if ([string]::IsNullOrWhiteSpace($databaseName)) {
    throw "wrangler.toml must define a D1 database_name for ACCOUNT_DB."
}

$databaseId = Get-ConfigValue $config "database_id"
if (-not [string]::IsNullOrWhiteSpace($databaseId) -and $databaseId -match 'wristbrief-account-db-id|placeholder|<|>') {
    throw "The D1 database_id is still a placeholder. Pull the latest gateway/wrangler.toml so Wrangler can provision by database_name."
}

$r2BucketName = Get-ConfigValue $config "bucket_name"
if ([string]::IsNullOrWhiteSpace($r2BucketName)) {
    throw "wrangler.toml must define an R2 bucket_name for TRANSCRIPTS_BUCKET."
}

$queueName = Get-ConfigValue $config "queue"
if ([string]::IsNullOrWhiteSpace($queueName)) {
    throw "wrangler.toml must define a Queue name for TRANSCRIPT_QUEUE."
}

$npm = Resolve-Tool "npm"
$npx = Resolve-Tool "npx"
$resolvedSecretsFile = if ($SkipSecrets) { $null } else { Resolve-SecretsFile $SecretsFile }
$databaseNeedsProvisioning = [string]::IsNullOrWhiteSpace($databaseId)

if ($DryRun -and -not [string]::IsNullOrWhiteSpace($HealthUrl)) {
    throw "-DryRun cannot be combined with -HealthUrl because no deployment is made."
}

Write-Host "WristBrief Gateway deploy" -ForegroundColor Green
Write-Host "Repository: $repoRoot"
Write-Host "Worker: $workerName"
Write-Host "D1: $databaseName$(if ($databaseNeedsProvisioning) { ' (auto-provision by name)' } else { " (ID $databaseId)" })"
Write-Host "R2: $r2BucketName"
Write-Host "Queue: $queueName"
if ($resolvedSecretsFile) {
    Write-Host "Secrets: local file selected (values will not be printed)"
}
else {
    Write-Warning "No local secrets file selected. Deployment continues, but AI/billing/auth routes need Cloudflare Secrets configured separately."
}

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

if (-not $DryRun -and -not $SkipMigrations -and -not $databaseNeedsProvisioning) {
    Invoke-RemoteMigrations $npx $InteractiveMigrations.IsPresent
}
elseif ($SkipMigrations) {
    Write-Warning "D1 migrations were skipped. Deploy only if the remote schema is already current."
}
elseif ($DryRun) {
    Write-Host "Dry run: remote migrations and secret uploads are skipped." -ForegroundColor Yellow
}

Write-Step "Provisioning declared Cloudflare resources and deploying Worker"
$deployArguments = @(
    "wrangler",
    "deploy",
    "--config",
    "wrangler.toml",
    "--keep-vars"
)
if ($DryRun) {
    $deployArguments += "--dry-run"
}
if ($SkipProvisioning) {
    $deployArguments += "--no-x-provision"
    Write-Warning "Automatic resource provisioning was disabled. Existing bindings must already be connected."
}
else {
    $deployArguments += @("--x-provision", "--x-auto-create")
    Write-Host "Wrangler will find or create the configured D1, R2, and Queue resources by name." -ForegroundColor Yellow
}
Invoke-GatewayCommand $npx $deployArguments

if ($resolvedSecretsFile -and -not $DryRun) {
    Write-Step "Uploading Cloudflare Worker secrets from the local file"
    Invoke-GatewayCommand $npx @(
        "wrangler",
        "secret",
        "bulk",
        $resolvedSecretsFile,
        "--name",
        $workerName,
        "--config",
        "wrangler.toml"
    )
}

if (-not $DryRun -and -not $SkipMigrations -and $databaseNeedsProvisioning) {
    Write-Host "The D1 binding was auto-provisioned during deploy; migrations run now against ACCOUNT_DB." -ForegroundColor Yellow
    Invoke-RemoteMigrations $npx $InteractiveMigrations.IsPresent
}

$updatedConfig = Get-Content -LiteralPath $configPath -Raw
$updatedDatabaseId = Get-ConfigValue $updatedConfig "database_id"
if (-not [string]::IsNullOrWhiteSpace($updatedDatabaseId)) {
    Write-Host "D1 database linked: $updatedDatabaseId" -ForegroundColor Green
    if ($updatedConfig -ne $config) {
        Write-Host "Wrangler wrote the provisioned resource ID back to gateway/wrangler.toml; review and commit that non-secret change if desired." -ForegroundColor Yellow
    }
}

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
    if ($DryRun) {
        Write-Host "Dry run completed. No Cloudflare resources, secrets, migrations, or Worker deployment were changed." -ForegroundColor Green
    }
    else {
        Write-Host "Deployment completed. Pass -HealthUrl https://<domain> to run /health automatically." -ForegroundColor Green
    }
}

Write-Host "`nNote: the repository currently declares a Queue producer but does not include a transcript Queue consumer." -ForegroundColor Yellow
