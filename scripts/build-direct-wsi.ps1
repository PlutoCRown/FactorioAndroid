[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$root=(Split-Path $PSScriptRoot -Parent);$zig=Join-Path $root 'tools\zig-x86_64-windows-0.15.2\zig.exe'
if((Get-FileHash $zig -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'd408dd38eed3e5204af841bcebf70502a4dbbb8399a3a3262be55059370bc018'){throw 'Compiler mismatch'}
$env:ZIG_GLOBAL_CACHE_DIR=Join-Path $root '.cache\zig-global';$env:ZIG_LOCAL_CACHE_DIR=Join-Path $root '.cache\zig-direct-wsi'
$src=Join-Path $root 'native-input\direct-wsi';$inc=Join-Path $src 'include';$vk=Join-Path $root 'tools\direct-zink-probe\include';$out=Join-Path $root 'artifacts\direct-wsi';New-Item -ItemType Directory -Force -Path $out,(Join-Path $out 'include\android')|Out-Null
Copy-Item (Join-Path $root 'tools\venus-host-build\baseline-ef25ad8b\sysroot\usr\include\android\dlext.h') (Join-Path $out 'include\android\dlext.h') -Force
$objects=@()
foreach($name in @('icd','instance','device','swapchain','wsi','native-window-surface')) {
 $source=if($name -eq 'native-window-surface'){Join-Path $src ($name+'.c')}else{Join-Path $src ('upstream\'+$name+'.c')}
 $object=Join-Path $out ($name+'.o');$arguments=@('cc','-target','aarch64-linux-gnu.2.38','-std=c11','-O2','-g','-fPIC','-fvisibility=hidden','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-I',$inc,'-I',$vk,'-I',(Join-Path $src 'upstream'),'-I',(Join-Path $out 'include'),'-c',$source,'-o',$object)
 $rsp=Join-Path $out ($name+'.rsp');[IO.File]::WriteAllLines($rsp,@($arguments[1..($arguments.Count-1)]|ForEach-Object {'"'+($_ -replace '\\','/')+'"'}),[Text.UTF8Encoding]::new($false))
 $saved=$ErrorActionPreference;$ErrorActionPreference='Continue';$lines=@(& $zig cc ('@'+$rsp) 2>&1|ForEach-Object {[string]$_});$code=$LASTEXITCODE;$ErrorActionPreference=$saved;$lines|Set-Content (Join-Path $out ($name+'.txt')) -Encoding UTF8;if($code){throw ($name+': '+($lines -join "`n"))};$objects+=$object
}
$stage=Join-Path $root 'app\src\main\jniLibs\arm64-v8a';$libs=@('librea_hybris_common.so','librea_direct_hardware.so','librea_direct_gralloc.so','librea_direct_ui.so')|ForEach-Object {Join-Path $stage $_}
# Existing XCB/Xlib payload names are obtained from fixed manifests by SONAME alias.
foreach($manifest in @('window-clients.json','direct-dependencies.json')) {
 $p=Join-Path $root ('app\src\main\assets\'+$manifest);if(Test-Path $p){$j=Get-Content $p -Raw|ConvertFrom-Json;foreach($row in $j.libraries){if(($row.name -eq 'libxcb.so.1') -or ($row.name -eq 'libX11-xcb.so.1') -or ($row.aliases -contains 'libX11-xcb.so.1')){$libs+=Join-Path $stage $row.packagedName}}}
}
$dso=Join-Path $out 'librea_direct_icd.so';$args=@('cc','-target','aarch64-linux-gnu.2.38','-shared','-Wl,--no-undefined','-Wl,--allow-shlib-undefined','-Wl,-soname,librea_direct_icd.so')+$objects+$libs+@('-pthread','-ldl','-o',$dso)
$rsp=Join-Path $out 'link.rsp';[IO.File]::WriteAllLines($rsp,@($args[1..($args.Count-1)]|ForEach-Object {'"'+($_ -replace '\\','/')+'"'}),[Text.UTF8Encoding]::new($false))
$saved=$ErrorActionPreference;$ErrorActionPreference='Continue';$lines=@(& $zig cc ('@'+$rsp) 2>&1|ForEach-Object {[string]$_});$code=$LASTEXITCODE;$ErrorActionPreference=$saved;if($code){throw ($lines -join "`n")};Copy-Item $dso (Join-Path $stage 'librea_direct_icd.so') -Force
Write-Output ('__FACTORIO_REPORT__'+([pscustomobject]@{Output=$dso;Sha256=(Get-FileHash $dso -Algorithm SHA256).Hash.ToLowerInvariant();Bytes=(Get-Item $dso).Length;OwnProviderSha256=(Get-FileHash (Join-Path $src 'native-window-surface.c') -Algorithm SHA256).Hash.ToLowerInvariant();Scope='Public ICD source + own Surface AHB provider, no game patch'}|ConvertTo-Json -Compress))
