# Project-local JDK/SDK only. No installer, registry, PATH or system SDK changes.
param([string]$Proxy='')
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
    $javaDownload=Join-Path $taskTools 'jdk-17\bin\java.exe'
    if(Test-Path $javaDownload) {
        $downloadProxy=if($Proxy){$Proxy}else{'-'}
        & $javaDownload (Join-Path $PSScriptRoot 'DownloadTool.java') $item.url $partial $downloadProxy | Out-Host
    } else {
        $proxyArgs=@();if($Proxy){$proxyArgs=@('--proxy',$Proxy)}
        & curl.exe @proxyArgs --silent --show-error --fail --location --retry 3 --connect-timeout 30 --max-time 1800 --continue-at - --output $partial $item.url
    }
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
# Reuse an existing manager to populate a separate project SDK when available.
if(-not (Test-Path $taskSdkManager)) {
    foreach($candidate in @((Join-Path 'D:\Android\Sdk' 'cmdline-tools\latest\bin\sdkmanager.bat'),(Join-Path 'D:\Android\Sdk' 'tools\bin\sdkmanager.bat'))) {
        if(Test-Path $candidate){$taskSdkManager=$candidate;break}
    }
}
if(-not (Test-Path $taskSdkManager)) {
    $taskArchive=Get-TaskArchive $taskManifest.commandlineTools 'commandline-tools-windows.zip'
    $taskExtract=Join-Path $taskCache 'sdk-extract'
    if(Test-Path $taskExtract){Remove-Item $taskExtract -Recurse -Force}
    Expand-Archive $taskArchive $taskExtract
    New-Item -ItemType Directory -Force -Path (Join-Path $taskSdk 'cmdline-tools')|Out-Null
    Move-Item (Join-Path $taskExtract 'cmdline-tools') (Join-Path $taskSdk 'cmdline-tools\16.0')
    Remove-Item $taskExtract -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $taskSdk|Out-Null
$env:JAVA_HOME=$taskJdk;$env:PATH=(Join-Path $taskJdk 'bin')+';'+$env:PATH
if($Proxy){$taskProxyUri=[uri]$Proxy;$env:JAVA_TOOL_OPTIONS='-Dhttps.proxyHost='+$taskProxyUri.Host+' -Dhttps.proxyPort='+$taskProxyUri.Port+' -Dhttp.proxyHost='+$taskProxyUri.Host+' -Dhttp.proxyPort='+$taskProxyUri.Port}
$taskProxyArgs=@();if($Proxy){$taskProxyArgs=@('--proxy=http',('--proxy_host='+$taskProxyUri.Host),('--proxy_port='+$taskProxyUri.Port))}
$taskSaved=$ErrorActionPreference;$ErrorActionPreference='Continue'
# Licenses only affect the project-local SDK, as in the existing CI setup.
1..100|ForEach-Object {'y'}|& $taskSdkManager @taskProxyArgs ('--sdk_root='+$taskSdk) --licenses|Out-Host
$taskLicenseExit=$LASTEXITCODE
& $taskSdkManager @taskProxyArgs ('--sdk_root='+$taskSdk) 'platforms;android-36' 'build-tools;36.0.0'|Out-Host
$taskSdkExit=$LASTEXITCODE;$ErrorActionPreference=$taskSaved
if($taskLicenseExit -or $taskSdkExit){throw ('SDK setup failed: licenses='+$taskLicenseExit+' packages='+$taskSdkExit)}
[pscustomobject]@{Jdk=$taskJdk;Sdk=$taskSdk;ProjectLocal=$true;SystemToolsChanged=$false}|ConvertTo-Json -Compress|Write-Host
