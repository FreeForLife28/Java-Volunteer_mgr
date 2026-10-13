$ErrorActionPreference = 'Stop'

if (-not $env:LOCALAPPDATA) { throw 'LOCALAPPDATA is not set.' }
$installDirectory = [System.IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'VolunteerHub\jm'))

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath) {
    $pathEntries = @($userPath -split ';' | Where-Object {
        $_ -and -not [string]::Equals($_.TrimEnd('\'), $installDirectory.TrimEnd('\'),
            [StringComparison]::OrdinalIgnoreCase)
    })
    [Environment]::SetEnvironmentVariable('Path', ($pathEntries -join ';'), 'User')
}
if (Test-Path -LiteralPath $installDirectory) {
    Remove-Item -LiteralPath $installDirectory -Recurse -Force
}
Write-Host "Removed jm from $installDirectory"
Write-Host 'Project-local tools under .jm and the repository launcher were left unchanged.'
