param(
    [string]$NodeVersion = "v24.18.1",
    [string]$OutputDirectory = "dist\release"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDirectory))
if (-not $resolvedOutput.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Output directory must stay inside the repository"
}

$stage = Join-Path $resolvedOutput "gtnh-ai-bot-windows-x64"
if (Test-Path -LiteralPath $stage) {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path (Join-Path $stage "app"), (Join-Path $stage "runtime"), (Join-Path $stage "mod") | Out-Null

Push-Location (Join-Path $repoRoot "companion")
try {
    pnpm install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw "pnpm install failed" }
    pnpm build
    if ($LASTEXITCODE -ne 0) { throw "pnpm build failed" }
    pnpm --filter server deploy --prod --legacy (Join-Path $stage "app")
    if ($LASTEXITCODE -ne 0) { throw "pnpm deploy failed" }
    New-Item -ItemType Directory -Force -Path (Join-Path $stage "app\dist") | Out-Null
    Copy-Item -LiteralPath "apps\server\dist\index.mjs" -Destination (Join-Path $stage "app\dist\index.mjs") -Force
    Copy-Item -LiteralPath "apps\web\dist" -Destination (Join-Path $stage "app\web") -Recurse -Force
    Copy-Item -LiteralPath "packages\db\src\migrations" -Destination (Join-Path $stage "app\migrations") -Recurse -Force
    $env:CI = "true"
    pnpm install --frozen-lockfile --prod=false
    if ($LASTEXITCODE -ne 0) { throw "pnpm development dependency restore failed" }
} finally {
    Pop-Location
}

Push-Location $repoRoot
try {
    .\gradlew.bat --no-configuration-cache build
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
}
$jar = Get-ChildItem -LiteralPath (Join-Path $repoRoot "build\libs") -Filter "*.jar" | Where-Object { $_.Name -notmatch "sources|dev|javadoc" } | Select-Object -First 1
if (-not $jar) { throw "Mod JAR was not produced" }
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $stage "mod\gtnh-ai-bot.jar")

$nodeZip = Join-Path $env:TEMP ("node-" + [Guid]::NewGuid().ToString("N") + ".zip")
$nodeExtract = Join-Path $env:TEMP ("node-" + [Guid]::NewGuid().ToString("N"))
Invoke-WebRequest -Uri "https://nodejs.org/dist/$NodeVersion/node-$NodeVersion-win-x64.zip" -OutFile $nodeZip
Expand-Archive -LiteralPath $nodeZip -DestinationPath $nodeExtract
$nodeRoot = Get-ChildItem -LiteralPath $nodeExtract -Directory | Select-Object -First 1
Copy-Item -LiteralPath (Join-Path $nodeRoot.FullName "node.exe") -Destination (Join-Path $stage "runtime\node.exe")
Copy-Item -LiteralPath (Join-Path $nodeRoot.FullName "LICENSE") -Destination (Join-Path $stage "runtime\NODE-LICENSE.txt")
Copy-Item -LiteralPath (Join-Path $repoRoot "packaging\start-gtnh-ai-bot.cmd") -Destination $stage
Copy-Item -LiteralPath (Join-Path $repoRoot "LICENSE") -Destination $stage
Copy-Item -LiteralPath (Join-Path $repoRoot "THIRD_PARTY_NOTICES.md") -Destination $stage

$archive = Join-Path $resolvedOutput "gtnh-ai-bot-windows-x64.zip"
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
Compress-Archive -LiteralPath $stage -DestinationPath $archive
Write-Host "Created $archive"
