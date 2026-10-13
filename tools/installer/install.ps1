$ErrorActionPreference = 'Stop'

$source = Join-Path $PSScriptRoot '..\jm\src\Jm.java'
$installDirectory = Join-Path $env:LOCALAPPDATA 'VolunteerHub\jm'
$classesDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("volunteer-jm-install-" + [guid]::NewGuid().ToString('N'))
$jarPath = Join-Path $installDirectory 'jm.jar'

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'JDK 11 or newer is required. Install a JDK and make sure javac is on PATH.'
}
if (-not (Get-Command jar -ErrorAction SilentlyContinue)) {
    throw 'The JDK jar tool was not found on PATH.'
}
if (-not $env:LOCALAPPDATA) {
    throw 'LOCALAPPDATA is not set.'
}

New-Item -ItemType Directory -Path $classesDirectory | Out-Null
New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
try {
    & javac --release 11 -encoding UTF-8 -d $classesDirectory $source
    if ($LASTEXITCODE -ne 0) { throw "Could not compile jm (javac exit code $LASTEXITCODE)." }
    & jar --create --file $jarPath --main-class Jm -C $classesDirectory .
    if ($LASTEXITCODE -ne 0) { throw "Could not package jm (jar exit code $LASTEXITCODE)." }

    $launcher = Join-Path $installDirectory 'jm.cmd'
    @('@echo off', 'java -jar "%~dp0jm.jar" %*', 'exit /b %ERRORLEVEL%') |
        Set-Content -LiteralPath $launcher -Encoding Ascii

    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $pathEntries = @()
    if ($userPath) { $pathEntries = @($userPath -split ';' | Where-Object { $_ }) }
    $alreadyRegistered = $false
    foreach ($entry in $pathEntries) {
        if ([string]::Equals($entry.TrimEnd('\'), $installDirectory.TrimEnd('\'),
                [StringComparison]::OrdinalIgnoreCase)) { $alreadyRegistered = $true; break }
    }
    if (-not $alreadyRegistered) {
        [Environment]::SetEnvironmentVariable('Path', (@($pathEntries) + $installDirectory) -join ';', 'User')
    }
}
finally {
    Remove-Item -LiteralPath $classesDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Installed jm in $installDirectory"
Write-Host 'Open a new terminal, then run: jm init'
