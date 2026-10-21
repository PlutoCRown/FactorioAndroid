# Project-local JDK/SDK only. No installer, registry, PATH or system SDK changes.
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskTools=Join-Path $taskRoot 'tools';$taskCache=Join-Path $taskRoot '.cache\toolchain'
New-Item -ItemType Directory -Force -Path $taskTools,$taskCache|Out-Null
$taskManifest=Get-Content (Join-Path $PSScriptRoot 'windows-toolchain.json') -Raw|ConvertFrom-Json
function Get-TaskArchive($item,[string]$name) {
    $dest=Join-Path $taskCache $name
    $algorithm=($item.checksumType).ToUpperInvariant()
    if((Test-Path $dest)-and ((Get-FileHash $dest -Algorithm $algorithm).Hash.ToLowerInvariant() -eq $item.checksum)){return $dest}
    $partial=$dest+'.part'
    Write-Host ('Downloading project-local '+$name)
    & curl.exe --silent --show-error --fail --location --retry 3 --connect-timeout 30 --max-time 1800 --continue-at - --output $partial $item.url
    if($LASTEXITCODE){throw ('Download failed: '+$name)}
    if((Get-Item $partial).Length -ne $item.bytes -or (Get-FileHash $partial -Algorithm $algorithm).Hash.ToLowerInvariant() -ne $item.checksum){throw ('Tool archive verification failed: '+$name)}
    Move-Item $partial $dest -Force;return $dest
}
$taskJdk=Join-Path $taskTools 'jdk-17'
if(-not (Test-Path (Join-Path $taskJdk 'bin\java.exe'))) {
    $taskArchive=Get-TaskArchive $taskManifest.jdk 'jdk17-windows-x64.zip'
    $taskExtract=Join-Path $taskCache 'jdk-extract'
    if(Test-Path $taskExtract){Remove-Item $taskExtract -Recurse -Force}
    Expand-Archive $taskArchive $taskExtract
    $taskCandidate=@(Get-ChildItem $taskExtract -Directory|Where-Object {Test-Path (Join-Path $_.FullName 'bin\java.exe')})
    if($taskCandidate.Count -ne 1){throw 'JDK layout mismatch'}
    Move-Item $taskCandidate[0].FullName $taskJdk
    Remove-Item $taskExtract -Recurse -Force
}
$taskSdk=Join-Path $taskTools 'android-sdk';$taskSdkManager=Join-Path $taskSdk 'cmdline-tools\16.0\bin\sdkmanager.bat'
if(-not (Test-Path $taskSdkManager)) {
    $taskArchive=Get-TaskArchive $taskManifest.commandlineTools 'commandline-tools-windows.zip'
    $taskExtract=Join-Path $taskCache 'sdk-extract'
    if(Test-Path $taskExtract){Remove-Item $taskExtract -Recurse -Force}
    Expand-Archive $taskArchive $taskExtract
    New-Item -ItemType Directory -Force -Path (Join-Path $taskSdk 'cmdline-tools')|Out-Null
    Move-Item (Join-Path $taskExtract 'cmdline-tools') (Join-Path $taskSdk 'cmdline-tools\16.0')
    Remove-Item $taskExtract -Recurse -Force
}
$env:JAVA_HOME=$taskJdk;$env:PATH=(Join-Path $taskJdk 'bin')+';'+$env:PATH
$taskSaved=$ErrorActionPreference;$ErrorActionPreference='Continue'
# Licenses only affect the project-local SDK, as in the existing CI setup.
1..100|ForEach-Object {'y'}|& $taskSdkManager ('--sdk_root='+$taskSdk) --licenses|Out-Host
$taskLicenseExit=$LASTEXITCODE
& $taskSdkManager ('--sdk_root='+$taskSdk) 'platforms;android-36' 'build-tools;36.0.0'|Out-Host
$taskSdkExit=$LASTEXITCODE;$ErrorActionPreference=$taskSaved
if($taskLicenseExit -or $taskSdkExit){throw ('SDK setup failed: licenses='+$taskLicenseExit+' packages='+$taskSdkExit)}
[pscustomobject]@{Jdk=$taskJdk;Sdk=$taskSdk;ProjectLocal=$true;SystemToolsChanged=$false}|ConvertTo-Json -Compress|Write-Host
