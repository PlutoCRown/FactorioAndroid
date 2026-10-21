param([string]$Sdk='', [string]$Jdk='', [switch]$PerformancePackage)
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
if(-not $Jdk) {
    $Jdk=Join-Path $taskRoot 'tools\jdk-17'
    if(-not (Test-Path (Join-Path $Jdk 'bin\java.exe'))) {$Jdk=$env:JAVA_HOME}
}
if(-not $Sdk) {
    $Sdk=Join-Path $taskRoot 'tools\android-sdk'
    if(-not (Test-Path $Sdk)) {$Sdk=$env:ANDROID_HOME}
    if(-not $Sdk) {$Sdk='D:\Android\Sdk'}
}
if(-not $Jdk -or -not (Test-Path (Join-Path $Jdk 'bin\java.exe'))) {throw 'JDK 17+ required. Run scripts/prepare-windows-toolchain.ps1 or pass -Jdk.'}
if(-not (Test-Path $Sdk)) {throw 'Android SDK required. Run scripts/prepare-windows-toolchain.ps1 or pass -Sdk.'}
$env:JAVA_HOME=$Jdk;$env:ANDROID_HOME=$Sdk;$env:ANDROID_SDK_ROOT=$Sdk
if(-not $env:GRADLE_USER_HOME){$env:GRADLE_USER_HOME=Join-Path $taskRoot '.cache\gradle'}
$env:PATH=(Join-Path $Jdk 'bin')+';'+$env:PATH
Set-Location $taskRoot
$taskArgs=@((Join-Path $taskRoot 'scripts\BuildLauncher.java'),'--root',$taskRoot)
if($PerformancePackage){$taskArgs+='--performance-package'}
$taskSaved=$ErrorActionPreference;$ErrorActionPreference='Continue'
& (Join-Path $Jdk 'bin\java.exe') @taskArgs | Out-Host
$taskCode=$LASTEXITCODE;$ErrorActionPreference=$taskSaved
if($taskCode -ne 0){throw ('Gradle launcher build failed: '+$taskCode)}
