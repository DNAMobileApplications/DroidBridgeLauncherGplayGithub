$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$CacheDir = Join-Path $ScriptDir "cache"
$OutputDir = Join-Path $ScriptDir "prebuilt\arm64-v8a"
$AarFile = Join-Path $CacheDir "sherpa-onnx-static-link-onnxruntime-1.13.3.aar"
$TargetFile = Join-Path $OutputDir "libsherpa-onnx-jni.so"
$MarkerFile = Join-Path $OutputDir ".source-sha256"
$DefaultUrl = "https://huggingface.co/csukuangfj2/sherpa-onnx-libs/resolve/86cc7834ba16b3d1cdbc4fe69e362ccdae10a48a/android/aar/1.13.3/sherpa-onnx-static-link-onnxruntime-1.13.3.aar?download=true"
$DefaultSha256 = "9f065fe6f2cab09fd48eaa580097293e077637ad53a5e89c5c58a36509386ac7"
$DefaultSize = 38398784L
$AarUrl = if ($env:DROIDBRIDGE_SHERPA_AAR_URL) { $env:DROIDBRIDGE_SHERPA_AAR_URL } else { $DefaultUrl }
$AarSha256 = if ($env:DROIDBRIDGE_SHERPA_AAR_SHA256) { $env:DROIDBRIDGE_SHERPA_AAR_SHA256.ToLowerInvariant() } else { $DefaultSha256 }
$AarSize = if ($env:DROIDBRIDGE_SHERPA_AAR_SIZE) { [Int64]$env:DROIDBRIDGE_SHERPA_AAR_SIZE } else { $DefaultSize }

New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

function Test-Arm64Elf([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -le 1MB) { return $false }
    $stream = [IO.File]::OpenRead($Path)
    try {
        $header = New-Object byte[] 20
        if ($stream.Read($header, 0, $header.Length) -lt 20) { return $false }
        $machine = [int]$header[18] -bor ([int]$header[19] -shl 8)
        return $header[0] -eq 0x7f -and $header[1] -eq 0x45 -and
            $header[2] -eq 0x4c -and $header[3] -eq 0x46 -and
            $header[4] -eq 2 -and $header[5] -eq 1 -and $machine -eq 183
    } finally {
        $stream.Dispose()
    }
}

if ((Test-Arm64Elf $TargetFile) -and (Test-Path -LiteralPath $MarkerFile)) {
    $marker = (Get-Content -LiteralPath $MarkerFile -Raw).Trim().ToLowerInvariant()
    if ($marker -eq $AarSha256) {
        Write-Output "DroidBridge Verity Daniel: Android ARM64 Sherpa JNI already prepared."
        exit 0
    }
}

$needDownload = $true
if (Test-Path -LiteralPath $AarFile -PathType Leaf) {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $AarFile).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $AarFile).Length
    if ($hash -eq $AarSha256 -and $size -eq $AarSize) {
        $needDownload = $false
    } else {
        Remove-Item -Force -LiteralPath $AarFile
    }
}

if ($needDownload) {
    $temp = "$AarFile.download"
    Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $temp
    Write-Output "DroidBridge Verity Daniel: downloading official Sherpa-ONNX 1.13.3 Android AAR..."
    Invoke-WebRequest -UseBasicParsing -MaximumRedirection 10 -Uri $AarUrl -OutFile $temp
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $temp).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $temp).Length
    if ($hash -ne $AarSha256) {
        Remove-Item -Force -LiteralPath $temp
        throw "DroidBridge Verity Daniel: AAR SHA-256 mismatch: $hash"
    }
    if ($size -ne $AarSize) {
        Remove-Item -Force -LiteralPath $temp
        throw "DroidBridge Verity Daniel: AAR size mismatch: $size"
    }
    Move-Item -Force -LiteralPath $temp -Destination $AarFile
}

$nativeTemp = "$TargetFile.download"
Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $nativeTemp
$archive = [IO.Compression.ZipFile]::OpenRead($AarFile)
try {
    $matches = @($archive.Entries | Where-Object {
        $_.FullName.Replace("\", "/").ToLowerInvariant().EndsWith(
            "/arm64-v8a/libsherpa-onnx-jni.so"
        )
    })
    if ($matches.Count -ne 1) {
        throw "Expected exactly one arm64-v8a/libsherpa-onnx-jni.so entry; found $($matches.Count)"
    }
    $source = $matches[0].Open()
    $destination = [IO.File]::Create($nativeTemp)
    try {
        $source.CopyTo($destination)
    } finally {
        $destination.Dispose()
        $source.Dispose()
    }
} finally {
    $archive.Dispose()
}

if (-not (Test-Arm64Elf $nativeTemp)) {
    Remove-Item -Force -LiteralPath $nativeTemp
    throw "DroidBridge Verity Daniel: extracted JNI library is not Android ARM64 ELF."
}
Move-Item -Force -LiteralPath $nativeTemp -Destination $TargetFile
[IO.File]::WriteAllText($MarkerFile, $AarSha256)
Write-Output "DroidBridge Verity Daniel: official Android ARM64 Sherpa JNI prepared."
