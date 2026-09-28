@echo off
setlocal
set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "CMAKE=D:\dev\android_sdk\cmake\3.22.1\bin\cmake.exe"
set "MNN=D:\3rd-party-projects\MNN"
set "BUILD=%MNN%\build_win64"
set "NINJA=D:\dev\miniconda3\Scripts\ninja.exe"

call "%VCVARS%" || exit /b 1
if not exist "%BUILD%" mkdir "%BUILD%"

echo === configure ===
"%CMAKE%" -S "%MNN%" -B "%BUILD%" -G Ninja ^
  -DCMAKE_MAKE_PROGRAM="%NINJA%" ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DMNN_BUILD_LLM=ON ^
  -DMNN_LOW_MEMORY=ON ^
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON ^
  -DMNN_SEP_BUILD=OFF ^
  -DMNN_BUILD_TOOLS=OFF ^
  -DMNN_BUILD_DEMO=OFF ^
  -DMNN_LLM_BUILD_DEMO=ON
if errorlevel 1 exit /b 1

echo === build ===
"%CMAKE%" --build "%BUILD%" --target MNN -j
if errorlevel 1 exit /b 1

echo === done ===
dir "%BUILD%\MNN.lib" "%BUILD%\MNN.dll" 2>nul
exit /b 0
