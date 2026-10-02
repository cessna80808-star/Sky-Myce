param([switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Install Node.js 22.12 or newer first.' }
    node -e "const [a,b]=process.versions.node.split('.').map(Number); if(a<22 || (a===22 && b<12)) process.exit(1)"
    if ($LASTEXITCODE -ne 0) { throw 'Node.js 22.12 or newer is required.' }
    if (-not (Test-Path -LiteralPath '.env')) { Copy-Item -LiteralPath '.env.example' -Destination '.env' }
    if (-not $SkipInstall) {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
    }
    npm run check
    if ($LASTEXITCODE -ne 0) { throw 'Project check failed.' }
    npm test
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
    Write-Output 'Prepared. Configure the ignored .env, review README.md, then run npm start.'
    Write-Output 'Preview guild commands: npm run register:user. No Discord settings were changed.'
} finally { Pop-Location }
