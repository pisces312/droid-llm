# Build and run the MNN PC host regression (docs/mnn-pc-regression.md).
# English-only comments/strings (AGENTS.md "Scripts and output").
#
# Usage:
#   scripts/mnn_host_regress.ps1            # build + run all cases
#   scripts/mnn_host_regress.ps1 -Cases unit
#   scripts/mnn_host_regress.ps1 -Configure # force re-configure

param(
    [string]$Model = "D:\models\LFM2-350M-MNN",
    [string]$Cases = "all",
    [switch]$Configure
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$hostSrc = Join-Path $repo "engine\mnn\src\main\cpp\host"
$buildDir = Join-Path $repo "build\mnn_host"

$mnnRoot = $env:MNN_ROOT
if (-not $mnnRoot) {
    Write-Error "MNN_ROOT is not set. Point it at the MNN source tree."
}
$mnnWinBuild = Join-Path $mnnRoot "build_win64"

if (-not (Test-Path (Join-Path $Model "config.json"))) {
    Write-Error "Model not found at $Model (need config.json). See docs/mnn-pc-regression.md §2."
}

# Locate tools: prefer env, fall back to well-known local installs.
$cmake = $env:MIMO_CMAKE
if (-not $cmake) {
    $cand = "D:\dev\android_sdk\cmake\3.22.1\bin\cmake.exe"
    if (Test-Path $cand) { $cmake = $cand } else { $cmake = "cmake" }
}
$ninja = $env:MIMO_NINJA
if (-not $ninja) {
    $cand = "D:\dev\miniconda3\Scripts\ninja.exe"
    if (Test-Path $cand) { $ninja = $cand } else { $ninja = "ninja" }
}

# MSVC env (VS 18 BuildTools). Required so the host exe links the CRT that
# MNN.lib was built with.
$vcvars = "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if (-not (Test-Path $vcvars)) {
    Write-Error "vcvars64.bat not found at $vcvars. Install VS BuildTools with MSVC."
}

Write-Host "mnn_host_regress"
Write-Host "  model      : $Model"
Write-Host "  MNN_ROOT   : $mnnRoot"
Write-Host "  win build  : $mnnWinBuild"
Write-Host "  host build : $buildDir"

$mnnLib = Join-Path $mnnWinBuild "MNN.lib"
if (-not (Test-Path $mnnLib)) {
    Write-Host ""
    Write-Host "MNN.lib missing. Configure + build MNN for Windows once:"
    Write-Host "  cmd /c `"`"$vcvars`" && cmake -S `"$mnnRoot`" -B `"$mnnWinBuild`" -G Ninja -DCMAKE_BUILD_TYPE=Release -DMNN_BUILD_LLM=ON -DMNN_LOW_MEMORY=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DMNN_SEP_BUILD=OFF -DMNN_BUILD_TOOLS=OFF`""
    Write-Host "  cmd /c `"`"$vcvars`" && cmake --build `"$mnnWinBuild`" -j`""
    Write-Error "Build MNN host libs first (docs/mnn-pc-regression.md §3)."
}

New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$configureNeeded = $Configure -or -not (Test-Path (Join-Path $buildDir "CMakeCache.txt"))
if ($configureNeeded) {
    Write-Host "configuring host harness..."
    & cmd /c "`"$vcvars`" && `"$cmake`" -S `"$hostSrc`" -B `"$buildDir`" -G Ninja -DCMAKE_BUILD_TYPE=Release -DMNN_ROOT=`"$mnnRoot`" -DMNN_WIN_BUILD=`"$mnnWinBuild`""
    if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }
}

Write-Host "building host harness..."
& cmd /c "`"$vcvars`" && `"$cmake`" --build `"$buildDir`""
if ($LASTEXITCODE -ne 0) { throw "cmake build failed" }

$exe = Join-Path $buildDir "mnn_host_test.exe"
if (-not (Test-Path $exe)) {
    throw "mnn_host_test.exe not produced at $exe"
}

Write-Host "running regression..."
& $exe --model $Model --cases $Cases
if ($LASTEXITCODE -ne 0) {
    Write-Error "mnn_host_test failed (exit $LASTEXITCODE)"
}
Write-Host "mnn_host_regress OK"
