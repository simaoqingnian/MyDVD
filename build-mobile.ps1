$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$jdk = Join-Path $project '.toolchain\jdk21\jdk-21.0.12.1+1'
$gradle = Join-Path $project '.toolchain\gradle\gradle-9.7.1\bin\gradle.bat'
$filmWord = Join-Path $project '..\FilmWord'
$sdk = Join-Path $filmWord '.android-sdk'
$python = 'D:\01_programs\python3_11_8\python.exe'

foreach ($path in @($jdk, $gradle, $sdk, $python)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "构建工具不存在：$path" }
}

$env:JAVA_HOME = (Resolve-Path -LiteralPath $jdk).Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'
$env:GRADLE_USER_HOME = Join-Path $filmWord '.gradle'
$env:MYDVD_PYTHON = (Resolve-Path -LiteralPath $python).Path

$sdkPath = ((Resolve-Path -LiteralPath $sdk).Path -replace '\\', '/')
Set-Content -LiteralPath (Join-Path $project 'local.properties') -Value "sdk.dir=$sdkPath" -Encoding ascii
& $gradle ':app:assembleMobileArm64_v8aDebug' '--no-daemon' '--console=plain' '--quiet'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "APK: $project\app\build\outputs\apk\mobileArm64_v8a\debug\app-mobile-arm64_v8a-debug.apk"
