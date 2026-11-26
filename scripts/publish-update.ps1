<#
.SYNOPSIS
Publishes the current build as an in-app update.

.DESCRIPTION
Builds the debug APK, copies it to updates/Kharcha-<versionName>.apk, rewrites
updates/latest.json, then commits and pushes those files to origin/main.
Installed copies of the app read latest.json and offer the APK.
Bump versionName / versionCode in app/build.gradle.kts before running this.

.PARAMETER Notes
Short release notes shown to the user in the Updates tab.

.EXAMPLE
.\scripts\publish-update.ps1 -Notes "Split payments now support % and exact amounts"
#>
param([Parameter(Mandatory)][string]$Notes)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradle = Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw
$version = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$code = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $version -or -not $code) { throw 'Could not read versionName/versionCode from app/build.gradle.kts' }

Push-Location $root
try {
    & .\gradlew.bat :app:assembleDebug --console=plain | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }

    $updates = Join-Path $root 'updates'
    New-Item -ItemType Directory -Force -Path $updates | Out-Null
    Get-ChildItem -Path $updates -Filter 'Kharcha-*.apk' | Remove-Item

    $apkName = "Kharcha-$version.apk"
    Copy-Item -Path 'app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path $updates $apkName)

    $manifest = [ordered]@{
        versionName = $version
        versionCode = [int]$code
        notes       = $Notes
        apk         = "updates/$apkName"
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText((Join-Path $updates 'latest.json'), $manifest, [System.Text.UTF8Encoding]::new($false))

    git add -- updates
    git commit -m "Publish update v$version" -m $Notes
    if ($LASTEXITCODE) { throw 'git commit failed' }
    git push origin main
    if ($LASTEXITCODE) { throw 'git push failed' }
    Write-Host "Published v$version"
}
finally {
    Pop-Location
}
