[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$taskRoot=(Split-Path $PSScriptRoot -Parent)
$taskZig=Join-Path $taskRoot 'tools\zig-x86_64-windows-0.15.2\zig.exe'
if((Get-FileHash $taskZig -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'd408dd38eed3e5204af841bcebf70502a4dbbb8399a3a3262be55059370bc018'){throw 'Compiler mismatch'}
$taskSource=Join-Path $taskRoot 'native-input\direct-present-server.c'
$taskBaseline=Join-Path $taskRoot 'tools\venus-host-build\baseline-ef25ad8b'
$taskInclude=Join-Path $taskRoot 'tools\direct-zink-probe\include'
$taskLinks=Join-Path $taskRoot 'tools\bionic-link-inputs'
$taskManifest=Get-Content (Join-Path $taskLinks 'manifest.json') -Raw|ConvertFrom-Json
foreach($name in @('libc.so','libdl.so')){$row=@($taskManifest.Libraries|Where-Object {$_.Name -eq $name});if($row.Count -ne 1 -or (Get-FileHash (Join-Path $taskLinks $name) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $row[0].Sha256){throw 'Bionic link input differs'}}
$taskOut=Join-Path $taskRoot 'artifacts\direct-present';New-Item -ItemType Directory -Force -Path $taskOut|Out-Null
$env:ZIG_GLOBAL_CACHE_DIR=Join-Path $taskRoot '.cache\zig-global';$env:ZIG_LOCAL_CACHE_DIR=Join-Path $taskRoot '.cache\zig-direct-present'
$object=Join-Path $taskOut 'direct-present.o';$dso=Join-Path $taskOut 'librea_direct_present.so'
$compile=@('cc','-target','aarch64-linux-android.26','-mcpu=generic+lse','-D_GNU_SOURCE=1','-std=c11','-O2','-fPIC','-fvisibility=hidden','-nostdlib','-Wall','-Wextra','-Werror','-I',$taskInclude,'-isystem',(Join-Path $taskBaseline 'sysroot\usr\include'),'-isystem',(Join-Path $taskBaseline 'sysroot\usr\include\aarch64-linux-android'),'-c',$taskSource,'-o',$object)
$link=@('ld.lld','-shared','--no-undefined','--allow-shlib-undefined','-z','relro','-z','now','--build-id=sha1','-soname','librea_direct_present.so',$object,(Join-Path $taskLinks 'libdl.so'),(Join-Path $taskLinks 'libc.so'),'-o',$dso)
foreach($step in @(@{Name='compile';Arguments=$compile},@{Name='link';Arguments=$link})){
 $v=$step.Arguments;$rsp=Join-Path $taskOut ($step.Name+'.rsp');[IO.File]::WriteAllLines($rsp,@($v[1..($v.Count-1)]|ForEach-Object {'"'+($_ -replace '\\','/')+'"'}),[Text.UTF8Encoding]::new($false));$saved=$ErrorActionPreference;$ErrorActionPreference='Continue';$lines=@(& $taskZig $v[0] ('@'+$rsp) 2>&1|ForEach-Object {[string]$_});$code=$LASTEXITCODE;$ErrorActionPreference=$saved;$lines|Set-Content (Join-Path $taskOut ($step.Name+'.txt')) -Encoding UTF8;if($code -ne 0){throw ($lines -join "`n")}
}
$staged=Join-Path $taskRoot 'app\src\main\jniLibs\arm64-v8a\librea_direct_present.so';Copy-Item $dso $staged -Force
Write-Output ('__FACTORIO_REPORT__'+([pscustomobject]@{Native=$dso;Sha256=(Get-FileHash $dso -Algorithm SHA256).Hash.ToLowerInvariant();SourceSha256=(Get-FileHash $taskSource -Algorithm SHA256).Hash.ToLowerInvariant();Staged=$staged;MacToolsInstalled=$false;DeviceExecuted=$false}|ConvertTo-Json -Compress))
