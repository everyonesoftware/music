#Requires -Version 7.2
<#
.SYNOPSIS
Build-only native trimming comparison and isolated installed upgrade acceptance.
.DESCRIPTION
Requires a disposable unlocked Windows account with no Music install/profile.
Never publishes. Fixture versions 0.1.32000/0.1.32001 are not public releases.
The shipping trimmed installer is tested separately from instrumented fixture builds.
Pinned Velopack 1.2.161 apply CLI: apply --package FILE --norestart --silent.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
    [ValidateRange(60, 600)][int]$OperationTimeoutSeconds = 240
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
if ((Test-Path -LiteralPath $evidence) -and @(Get-ChildItem -LiteralPath $evidence -Force).Count) {
    throw 'EvidenceDirectory must be new or empty.'
}
$null = New-Item -ItemType Directory -Path $evidence -Force
$summary = [ordered]@{
    schemaVersion = 1; status = 'failed'; sourceSha = $null
    fixtureVersions = @('0.1.32000', '0.1.32001'); publicRelease = $false
    shippingInstallerVerified = $false; seedVerified = $false; upgradeVerified = $false
    restorationVerified = $false; uninstallVerified = $false; mediaVerified = $false
    audibleOutputVerified = $false; failure = $null; cleanupErrors = @()
}
$registration = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicWindows'
$profile = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'EveryoneSoftware\Music'
$install = Join-Path ([IO.Path]::GetTempPath()) ('MusicWindows-trim-upgrade-' + [guid]::NewGuid().ToString('N'))
$mutationStarted = $false
$windows = Join-Path $root 'apps/windows'

function Write-Evidence($Value, [string]$Name) {
    ConvertTo-Json -InputObject $Value -Depth 12 | Set-Content -LiteralPath (Join-Path $evidence $Name) -Encoding utf8
}
function Invoke-Checked([string]$Executable, [string[]]$Arguments, [string]$Label, [string]$WorkingDirectory = $root) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    $started = $false
    try {
        if (-not $process.Start()) { throw "Could not start $Label" }
        $started = $true
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($OperationTimeoutSeconds * 1000)) {
            $process.Kill($true)
            $null = $process.WaitForExit(10000)
            throw "$Label exceeded the bounded operation timeout"
        }
        if (-not $stdout.Wait(5000) -or -not $stderr.Wait(5000)) { throw "$Label output streams did not close" }
        $stdout.Result | Set-Content -LiteralPath (Join-Path $evidence "$Label-stdout.txt")
        $stderr.Result | Set-Content -LiteralPath (Join-Path $evidence "$Label-stderr.txt")
        Write-Evidence @{ exitCode = $process.ExitCode; executable = $Executable; arguments = $Arguments } "$Label-process.json"
        if ($process.ExitCode -ne 0) { throw "$Label exited $($process.ExitCode); see retained diagnostics" }
    } finally {
        if ($started -and -not $process.HasExited) {
            $process.Kill($true)
            $null = $process.WaitForExit(10000)
        }
        $process.Dispose()
    }
}
function Get-OwnedProcesses {
    $prefix = $install.TrimEnd('\') + '\'
    Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
    }
}
function Get-OwnedLinks {
    $shell = New-Object -ComObject WScript.Shell
    try {
        $roots = @([Environment]::GetFolderPath('StartMenu'), [Environment]::GetFolderPath('CommonStartMenu'),
            [Environment]::GetFolderPath('DesktopDirectory'), [Environment]::GetFolderPath('CommonDesktopDirectory'))
        foreach ($directory in $roots | Select-Object -Unique) {
            if (-not $directory -or -not (Test-Path -LiteralPath $directory)) { continue }
            foreach ($file in Get-ChildItem -LiteralPath $directory -Filter '*.lnk' -Recurse -File) {
                $link = $shell.CreateShortcut($file.FullName)
                try {
                    if ($link.TargetPath.StartsWith($install.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
                        [pscustomobject]@{ path = $file.FullName; target = $link.TargetPath }
                    }
                } finally { $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($link) }
            }
        }
    } finally { $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell) }
}
function Remove-OwnedDirectory([string]$Directory, [string]$Label) {
    try {
        if (Test-Path -LiteralPath $Directory) { Remove-Item -LiteralPath $Directory -Recurse -Force -ErrorAction Stop }
        if (Test-Path -LiteralPath $Directory) { throw 'Owned directory survived removal.' }
    } catch { $summary.cleanupErrors += "$Label cleanup did not remove its owned directory." }
}
function Assert-Payload([string]$Directory, [string]$Version, [bool]$Trimmed) {
    foreach ($name in @('Music.exe', 'Music.dll', 'Music.runtimeconfig.json', 'Music.deps.json', 'coreclr.dll', 'hostfxr.dll',
        'Microsoft.UI.Xaml.dll', 'Microsoft.WindowsAppRuntime.dll', 'resources.pri', 'Assets\Music.ico', 'Assets\Music.png')) {
        $file = Get-Item -LiteralPath (Join-Path $Directory $name)
        if ($file.Length -le 0) { throw "Missing or empty native payload: $name" }
    }
    $runtime = Get-Content -LiteralPath (Join-Path $Directory 'Music.runtimeconfig.json') -Raw | ConvertFrom-Json -AsHashtable
    if (-not $runtime.runtimeOptions.ContainsKey('includedFrameworks') -or $runtime.runtimeOptions.ContainsKey('frameworks') -or $runtime.runtimeOptions.ContainsKey('framework')) {
        throw 'Payload must remain self-contained.'
    }
    $deps = Get-Content -LiteralPath (Join-Path $Directory 'Music.deps.json') -Raw | ConvertFrom-Json -AsHashtable
    if (-not $deps.libraries.ContainsKey("Music/$Version")) { throw 'Native payload version does not match its fixture package.' }
    if ($Trimmed) {
        foreach ($key in @('System.Text.Json.JsonSerializer.IsReflectionEnabledByDefault', 'System.Runtime.CompilerServices.RuntimeFeature.IsDynamicCodeSupported')) {
            if (-not $runtime.runtimeOptions.configProperties.ContainsKey($key) -or $runtime.runtimeOptions.configProperties[$key] -ne $false) {
                throw "Trim feature switch was not applied: $key"
            }
        }
    }
}
function Build-Package([string]$Name, [string]$Version, [bool]$Trimmed, [bool]$Instrumented) {
    $directory = Join-Path $evidence $Name
    $publish = Join-Path $directory 'publish'
    $releases = Join-Path $directory 'releases'
    $trim = $Trimmed.ToString().ToLowerInvariant()
    # Both source-inclusion states are explicit, never inherited from the runner.
    $native = if ($Instrumented) { '-p:MusicNativeAcceptance=true' } else { '-p:MusicNativeAcceptance=false' }
    Invoke-Checked -Executable 'dotnet' -Label "$Name-publish" -Arguments @(
        'publish', (Join-Path $windows 'Music.App/Music.App.csproj'), '-c', 'Release', '-r', 'win-x64', '--self-contained', 'true',
        "-p:MusicTrimmed=$trim", $native, "-p:Version=$Version", "-p:InformationalVersion=$Version+$($summary.sourceSha)", '-o', $publish)
    Assert-Payload $publish $Version $Trimmed
    Invoke-Checked -Executable 'dotnet' -WorkingDirectory $windows -Label "$Name-pack" -Arguments @(
        'tool', 'run', 'vpk', '--', '[win]', 'pack', '--packId', 'MusicWindows', '--packVersion', $Version,
        '--packDir', $publish, '--mainExe', 'Music.exe', '--packTitle', 'Music', '--packAuthors', 'Everyone Software',
        '--runtime', 'win-x64', '--channel', 'win-x64', '--framework', 'vcredist143-x64', '--noPortable', '--delta', 'None',
        '--shortcuts', 'StartMenuRoot', '--icon', (Join-Path $windows 'Music.App/Assets/Music.ico'), '--outputDir', $releases, '--yes', '--skip-updates')
    $setup = Get-Item -LiteralPath (Join-Path $releases 'MusicWindows-win-x64-Setup.exe')
    $packages = @(Get-ChildItem -LiteralPath $releases -Filter '*-full.nupkg' -File)
    if ($packages.Count -ne 1) { throw 'Expected exactly one full fixture update package.' }
    return @{ publish = $publish; setup = $setup.FullName; package = $packages[0].FullName; version = $Version
        publishBytes = (Get-ChildItem -LiteralPath $publish -Recurse -File | Measure-Object Length -Sum).Sum
        installerBytes = $setup.Length; installerSha256 = (Get-FileHash -LiteralPath $setup.FullName -Algorithm SHA256).Hash }
}
function Run-Stage([string]$Stage, [string]$Media) {
    $keys = @('MUSIC_NATIVE_ACCEPTANCE_DIRECTORY', 'MUSIC_NATIVE_ACCEPTANCE_STAGE', 'MUSIC_NATIVE_ACCEPTANCE_MEDIA', 'MUSIC_STARTUP_DIAGNOSTICS')
    $previous = @{}
    foreach ($key in $keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key) }
    try {
        $env:MUSIC_NATIVE_ACCEPTANCE_DIRECTORY = $evidence
        $env:MUSIC_NATIVE_ACCEPTANCE_STAGE = $Stage
        $env:MUSIC_NATIVE_ACCEPTANCE_MEDIA = $Media
        $env:MUSIC_STARTUP_DIAGNOSTICS = $evidence
        Invoke-Checked -Executable (Join-Path $install 'current\Music.exe') -Arguments @() -Label "native-$Stage"
    } finally {
        foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key]) }
    }
    $resultFile = if ($Stage -eq 'seed') { 'seed-result.json' } else { 'restore-result.json' }
    $result = Get-Content -LiteralPath (Join-Path $evidence $resultFile) -Raw | ConvertFrom-Json -AsHashtable
    if ($result.status -ne 'passed') { throw "Native $Stage scenario failed; see $resultFile" }
    $expectedVersion = if ($Stage -eq 'seed') { '0.1.32000' } else { '0.1.32001' }
    if ($result.TargetVersion -ne $expectedVersion -or $result.audibleOutputVerified -ne $false) {
        throw "Native $Stage evidence identity or audibility boundary mismatch."
    }
    return $result
}

try {
    if (-not $IsWindows) { throw 'Native Windows is required; this script cannot validate WinUI on Linux.' }
    if (-not [Environment]::UserInteractive -or (Get-Process -Id $PID).SessionId -eq 0) { throw 'An unlocked interactive Windows account is required.' }
    if (Test-Path -LiteralPath $profile) { throw 'Existing Music profile: refusing to alter user data.' }
    if (Test-Path -LiteralPath $registration) { throw 'Existing MusicWindows registration: use a disposable account.' }
    if (Test-Path -LiteralPath (Join-Path $env:LOCALAPPDATA 'MusicWindows')) { throw 'Existing default Music installation.' }
    $summary.sourceSha = (& git -C $root rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $summary.sourceSha -notmatch '^[0-9a-f]{40}$') { throw 'Could not identify source commit.' }
    $dirty = & git -C $root status --porcelain --untracked-files=all
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Native acceptance requires committed sources in a clean checkout.' }
    Invoke-Checked -Executable 'dotnet' -Label 'tool-restore' -Arguments @('tool', 'restore', '--tool-manifest', (Join-Path $windows '.config/dotnet-tools.json'))
    $plain = Build-Package 'untrimmed' '0.1.32000' $false $false
    $trimmed = Build-Package 'trimmed' '0.1.32001' $true $false
    Write-Evidence @{ sourceSha = $summary.sourceSha; fixtureBuilds = $true; untrimmed = $plain; trimmed = $trimmed
        publishBytesSaved = $plain.publishBytes - $trimmed.publishBytes
        installerBytesSaved = $plain.installerBytes - $trimmed.installerBytes } 'native-sizes.json'

    # Exact uninstrumented trimmed package: existing visible-window screenshot and uninstall gate.
    Invoke-Checked -Executable (Get-Process -Id $PID).Path -Label 'shipping-installer-smoke' -Arguments @(
        '-NoProfile', '-File', (Join-Path $PSScriptRoot 'test-windows-installer.ps1'),
        '-InstallerPath', $trimmed.setup, '-EvidenceDirectory', (Join-Path $evidence 'shipping-smoke'))
    $shipping = Get-Content -LiteralPath (Join-Path $evidence 'shipping-smoke/summary.json') -Raw | ConvertFrom-Json -AsHashtable
    if ($shipping.status -ne 'passed' -or -not $shipping.installVerified -or -not $shipping.windowVerified -or -not $shipping.uninstallVerified) {
        throw 'Incomplete uninstrumented installer lifecycle evidence.'
    }
    $summary.shippingInstallerVerified = $true

    $baseline = Build-Package 'native-baseline' '0.1.32000' $false $true
    $upgrade = Build-Package 'native-upgrade' '0.1.32001' $true $true
    # Explicit generated PCM WAV fixture, not recorded or synthesized success evidence.
    $media = Join-Path $evidence 'fixture.wav'
    $stream = [IO.File]::Create($media)
    $writer = [IO.BinaryWriter]::new($stream)
    try {
        $samples = 160000
        $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF')); $writer.Write([int](36 + $samples * 2))
        $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt ')); $writer.Write([int]16)
        $writer.Write([int16]1); $writer.Write([int16]1); $writer.Write([int]8000); $writer.Write([int]16000)
        $writer.Write([int16]2); $writer.Write([int16]16); $writer.Write([Text.Encoding]::ASCII.GetBytes('data'))
        $writer.Write([int]($samples * 2))
        for ($i = 0; $i -lt $samples; $i++) { $writer.Write([int16](2000 * [Math]::Sin(2 * [Math]::PI * 440 * $i / 8000))) }
    } finally { $writer.Dispose(); $stream.Dispose() }
    foreach ($service in @('AudioEndpointBuilder', 'Audiosrv')) {
        try { Start-Service -Name $service -ErrorAction Stop } catch { Write-Evidence @{ service = $service; available = $false } "service-$service.json" }
    }
    Write-Evidence @(Get-PnpDevice -Class AudioEndpoint -ErrorAction SilentlyContinue | Select-Object Status, Class, FriendlyName) 'audio-endpoints.json'

    if ((Test-Path -LiteralPath $profile) -or (Test-Path -LiteralPath $registration)) {
        throw 'Music state appeared during builds; refusing to overwrite it.'
    }
    $mutationStarted = $true
    Invoke-Checked -Executable $baseline.setup -Arguments @('--silent', '--installto', $install, '--log', (Join-Path $evidence 'baseline-install.log')) -Label 'baseline-install'
    Assert-Payload (Join-Path $install 'current') '0.1.32000' $false
    $before = Get-ItemProperty -LiteralPath $registration
    if ($before.DisplayVersion -ne '0.1.32000' -or $before.InstallLocation.TrimEnd('\') -ine $install.TrimEnd('\')) { throw 'Baseline installation identity mismatch.' }
    $installedLinks = @(Get-OwnedLinks)
    Write-Evidence $installedLinks 'upgrade-installed-shortcuts.json'
    if (-not $installedLinks.Count) { throw 'Baseline installer did not create an owned shortcut.' }
    $seed = Run-Stage 'seed' $media
    $summary.seedVerified = $true
    if (@(Get-OwnedProcesses).Count) { throw 'Seed app did not close before upgrade.' }
    $profileFile = Join-Path $profile 'connection.json'
    $profileHash = (Get-FileHash -LiteralPath $profileFile -Algorithm SHA256).Hash

    Invoke-Checked -Executable (Join-Path $install 'Update.exe') -Label 'apply-trimmed-upgrade' -Arguments @('apply', '--package', $upgrade.package, '--norestart', '--silent', '--log', (Join-Path $evidence 'apply.log'))
    Assert-Payload (Join-Path $install 'current') '0.1.32001' $true
    $after = Get-ItemProperty -LiteralPath $registration
    if ($after.DisplayVersion -ne '0.1.32001' -or $after.InstallLocation.TrimEnd('\') -ine $install.TrimEnd('\')) { throw 'Upgrade version or installation root mismatch.' }
    if ((Get-FileHash -LiteralPath $profileFile -Algorithm SHA256).Hash -ne $profileHash) { throw 'Upgrade replaced the saved profile.' }
    $summary.upgradeVerified = $true
    $restored = Run-Stage 'restore' $media
    $summary.restorationVerified = $true
    Write-Evidence @(Get-ChildItem -LiteralPath $install -Recurse -File | Select-Object FullName, Length) 'upgraded-installed-files.json'
    Invoke-Checked -Executable (Join-Path $install 'Update.exe') -Arguments @('--uninstall', '--silent', '--log', (Join-Path $evidence 'uninstall.log')) -Label 'upgrade-uninstall'
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $remaining = @(Get-ChildItem -LiteralPath $install -Recurse -File -ErrorAction SilentlyContinue)
        $remainingLinks = @(Get-OwnedLinks)
        if (-not $remaining.Count -and -not $remainingLinks.Count -and -not (Test-Path -LiteralPath $registration) -and -not @(Get-OwnedProcesses).Count) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    Write-Evidence $remainingLinks 'upgrade-remaining-shortcuts.json'
    if ($remaining.Count -or $remainingLinks.Count -or (Test-Path -LiteralPath $registration) -or @(Get-OwnedProcesses).Count) { throw 'Upgrade uninstall left files, shortcuts, registration or processes behind.' }
    $summary.uninstallVerified = $true
    $summary.mediaVerified = $restored.ContainsKey('media') -and $restored.media.status -eq 'passed'
    $summary.status = if ($summary.mediaVerified) { 'passed' } else { 'partial' }
} catch {
    $summary.failure = $_.ToString()
    $summary.status = 'failed'
} finally {
    if ($mutationStarted) {
        try {
            foreach ($owned in @(Get-OwnedProcesses)) {
                try {
                    $current = Get-Process -Id $owned.ProcessId -ErrorAction SilentlyContinue
                    if ($current -and $current.Path -eq $owned.ExecutablePath) { $current.Kill($true); $null = $current.WaitForExit(10000) }
                } catch { $summary.cleanupErrors += 'Could not stop a test-owned process.' }
            }
        } catch { $summary.cleanupErrors += 'Could not enumerate test-owned processes for cleanup.' }
        if (Test-Path -LiteralPath (Join-Path $install 'Update.exe')) {
            try { Invoke-Checked -Executable (Join-Path $install 'Update.exe') -Arguments @('--uninstall', '--silent') -Label 'cleanup-uninstall' }
            catch { $summary.cleanupErrors += 'Fallback supported uninstall did not complete.' }
        }
        try {
            foreach ($link in @(Get-OwnedLinks)) { Remove-Item -LiteralPath $link.path -Force -ErrorAction Stop }
            if (@(Get-OwnedLinks).Count) { throw 'Owned shortcuts survived cleanup.' }
        } catch { $summary.cleanupErrors += 'Could not remove and verify test-owned shortcuts.' }
        # Only this invocation's new isolated install/profile can reach this cleanup.
        Remove-OwnedDirectory $install 'Installation'
        Remove-OwnedDirectory $profile 'Fixture profile'
    }
    if ($summary.cleanupErrors.Count) { $summary.status = 'failed' }
    Write-Evidence $summary 'native-summary.json'
}
if ($summary.status -ne 'passed') { throw "Native acceptance is $($summary.status); no release is approved. See native-summary.json." }
