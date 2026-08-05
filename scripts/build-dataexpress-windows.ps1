[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$LazarusRoot,
    [Parameter(Mandatory = $true)] [string]$SourceRoot,
    [Parameter(Mandatory = $true)] [string]$ComponentsRoot,
    [Parameter(Mandatory = $true)] [string]$OutputRoot,
    [Parameter(Mandatory = $true)] [string]$PrimaryConfigPath
)

$ErrorActionPreference = 'Stop'

function Invoke-Lazbuild {
    param([string[]]$Arguments)

    Write-Host ('> lazbuild ' + ($Arguments -join ' '))
    & $script:Lazbuild @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "lazbuild failed with exit code $LASTEXITCODE"
    }
}

$script:Lazbuild = Join-Path $LazarusRoot 'lazbuild.exe'
if (-not (Test-Path -LiteralPath $script:Lazbuild)) {
    throw "lazbuild.exe was not found: $script:Lazbuild"
}

# A portable Lazarus extraction does not run the installer step that normally
# creates fpc.cfg. Generate the compiler's own stock configuration so the RTL,
# FCL and binutils paths resolve without requiring a machine-wide install.
$fpcExecutable = Get-ChildItem -LiteralPath (Join-Path $LazarusRoot 'fpc') `
    -Recurse -Filter 'fpc.exe' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.DirectoryName -like '*\bin\i386-win32' } |
    Select-Object -First 1
if ($null -ne $fpcExecutable) {
    $fpcBin = $fpcExecutable.DirectoryName
    $env:Path = "$fpcBin;$env:Path"
    $fpcConfig = Join-Path $fpcBin 'fpc.cfg'
    if (-not (Test-Path -LiteralPath $fpcConfig)) {
        $fpcMkCfg = Join-Path $fpcBin 'fpcmkcfg.exe'
        if (-not (Test-Path -LiteralPath $fpcMkCfg)) {
            throw "Portable FPC has no fpcmkcfg.exe: $fpcMkCfg"
        }
        $fpcBase = Split-Path -Parent (Split-Path -Parent $fpcBin)
        & $fpcMkCfg -d "basepath=$fpcBase" `
            -d "localbasepath=$env:LOCALAPPDATA\FreePascal" `
            -o $fpcConfig -s
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $fpcConfig)) {
            throw "Could not generate portable FPC configuration: $fpcConfig"
        }
    }
}

New-Item -ItemType Directory -Force -Path $PrimaryConfigPath | Out-Null
$common = @("--pcp=$PrimaryConfigPath")
$packages = @(
    (Join-Path $ComponentsRoot 'PascalScript\Source\pascalscript.lpk'),
    (Join-Path $ComponentsRoot 'PascalScript\Source\PascalScriptFCL.lpk'),
    (Join-Path $ComponentsRoot 'dcpcrypt\dcpcrypt.lpk'),
    (Join-Path $ComponentsRoot 'jvdesign\jvRuntimeDesign.lpk'),
    (Join-Path $ComponentsRoot 'kcontrols\packages\kcontrols\kcontrolslaz.lpk'),
    (Join-Path $ComponentsRoot 'dbctrlsex\datacontrolsex.lpk'),
    (Join-Path $ComponentsRoot 'bgra\bgrabitmap\bgrabitmappack.lpk'),
    (Join-Path $SourceRoot 'dxcomponents.lpk')
)

foreach ($package in $packages) {
    if (-not (Test-Path -LiteralPath $package)) {
        throw "Required Lazarus package was not found: $package"
    }
    Invoke-Lazbuild ($common + '--add-package-link' + $package)
}

$project = Join-Path $SourceRoot 'dataexpress.lpi'
Invoke-Lazbuild ($common + '--build-mode=Win32' + $project)

$executable = Join-Path $SourceRoot '_test\dataexpress.exe'
if (-not (Test-Path -LiteralPath $executable)) {
    throw "Build completed without the expected executable: $executable"
}

# Confirm that the output is a 32-bit x86 PE before embedding it in Box86/Wine.
$stream = [IO.File]::OpenRead($executable)
try {
    $reader = [IO.BinaryReader]::new($stream)
    if ($reader.ReadUInt16() -ne 0x5A4D) { throw 'DataExpress output is not a PE file' }
    $stream.Position = 0x3C
    $peOffset = $reader.ReadUInt32()
    $stream.Position = $peOffset
    if ($reader.ReadUInt32() -ne 0x00004550) { throw 'Invalid PE signature' }
    $machine = $reader.ReadUInt16()
    if ($machine -ne 0x014C) { throw ('Expected i386 PE, machine is 0x{0:X4}' -f $machine) }
}
finally {
    $stream.Dispose()
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$runtimeRoot = Join-Path $SourceRoot '_test'
Get-ChildItem -LiteralPath $runtimeRoot -Recurse -File | Where-Object {
    $relative = $_.FullName.Substring($runtimeRoot.Length).TrimStart('\')
    $relative -notin @('PadegUC.dll', 'dataexpress.exe')
} | ForEach-Object {
    $relative = $_.FullName.Substring($runtimeRoot.Length).TrimStart('\')
    $destination = Join-Path $OutputRoot $relative
    $parent = Split-Path -Parent $destination
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $destination -Force
}
Copy-Item -LiteralPath $executable -Destination (Join-Path $OutputRoot 'DataExpress.exe') -Force
Copy-Item -LiteralPath (Join-Path $SourceRoot 'LICENSE.txt') -Destination $OutputRoot -Force
Copy-Item -LiteralPath (Join-Path $SourceRoot 'NOTICE.txt') -Destination $OutputRoot -Force

Write-Host "DataExpress Win32 payload ready: $OutputRoot"
