param(
    [string]$ToolsRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) 'AndroidBuild'),
    [Parameter(ValueFromRemainingArguments)]
    [string[]]$Tasks = @(':app:testDebugUnitTest', ':app:assembleDebug')
)

$ErrorActionPreference = 'Stop'
$toolPath = [IO.Path]::GetFullPath($ToolsRoot)
$jdk = Get-ChildItem -LiteralPath (Join-Path $toolPath 'jdk') -Directory |
    Where-Object { $_.Name -like 'jdk-21*' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe')) } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jdk) { throw "Install JDK 21 under $toolPath\jdk." }

$sdk = Join-Path $toolPath 'android-sdk'
if (-not (Test-Path -LiteralPath $sdk -PathType Container)) { throw "Install Android SDK under $sdk." }

$userDir = Join-Path $toolPath 'java-home'
$tempDir = Join-Path $toolPath 'tmp'
$androidUserDir = Join-Path $toolPath 'android-user'
$gradleDir = Join-Path $toolPath 'gradle'
[void](New-Item -ItemType Directory -Path $userDir, $tempDir, $androidUserDir, $gradleDir -Force)

$variables = @{
    JAVA_HOME = $jdk.FullName
    ANDROID_HOME = $sdk
    ANDROID_SDK_ROOT = $sdk
    ANDROID_USER_HOME = $androidUserDir
    GRADLE_USER_HOME = $gradleDir
    JAVA_TOOL_OPTIONS = ('{0} "-Duser.home={1}" "-Djava.io.tmpdir={2}"' -f $env:JAVA_TOOL_OPTIONS, $userDir, $tempDir).Trim()
    TEMP = $tempDir
    TMP = $tempDir
}
$previous = @{}
foreach ($name in $variables.Keys) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $variables[$name], 'Process')
}

Push-Location -LiteralPath $PSScriptRoot
try {
    & .\gradlew.bat @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with code $LASTEXITCODE." }
} finally {
    Pop-Location
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
