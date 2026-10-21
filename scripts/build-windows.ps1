param(
    [string]$Sdk = 'D:\Android\Sdk',
    [string]$Jdk = 'D:\Java\jdk-15.0.2',
    [switch]$PerformancePackage
)
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
if(-not $PSScriptRoot) {$taskRoot=Join-Path ([Environment]::GetFolderPath('Desktop')) 'FactorioAndroid'}
else {$taskRoot=Split-Path $PSScriptRoot -Parent}
if (-not (Test-Path (Join-Path $taskRoot '.factorio-migration-workspace.json'))) {throw 'Workspace marker missing'}
$taskBuild=Join-Path $taskRoot 'build'
$taskTools=Join-Path $Sdk 'build-tools\33.0.1'
$taskAndroid=Join-Path $Sdk 'platforms\android-33\android.jar'
$taskCache=Join-Path $taskRoot '.cache'
$taskLocal=Join-Path $taskRoot '.local'
$taskArtifacts=Join-Path $taskRoot 'artifacts'
# Removed source/resource files must not survive as stale classes in the APK.
foreach($taskCleanName in @('classes','generated','dex','tests','compile-stubs','import-tests')) {
    $taskCleanPath=Join-Path $taskBuild $taskCleanName
    if(Test-Path -LiteralPath $taskCleanPath){Remove-Item -LiteralPath $taskCleanPath -Recurse -Force}
}
foreach($taskFolder in @($taskBuild,$taskCache,$taskLocal,$taskArtifacts,(Join-Path $taskBuild 'classes'),(Join-Path $taskBuild 'generated'),(Join-Path $taskBuild 'dex'),(Join-Path $taskBuild 'tests'),(Join-Path $taskCache 'tmp'))) {
    New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
}
$env:JAVA_HOME=$Jdk
$env:TEMP=Join-Path $taskCache 'tmp'
$env:TMP=$env:TEMP
$env:PATH=(Join-Path $Jdk 'bin')+';'+$env:PATH
function Invoke-TaskTool([string]$taskExecutable,[string[]]$taskArguments) {
    Write-Host ('TOOL '+[IO.Path]::GetFileName($taskExecutable))
    $taskSavedPreference=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    & $taskExecutable @taskArguments | Out-Host
    $taskExit=$LASTEXITCODE
    $ErrorActionPreference=$taskSavedPreference
    if($taskExit -ne 0) {throw ('Tool failed: '+$taskExecutable+' exit '+$taskExit)}
}
foreach($taskRequired in @($taskAndroid,(Join-Path $Jdk 'bin\javac.exe'),(Join-Path $taskTools 'aapt2.exe'),(Join-Path $taskTools 'd8.bat'),(Join-Path $taskTools 'zipalign.exe'),(Join-Path $taskTools 'apksigner.bat'))) {
    if(-not (Test-Path -LiteralPath $taskRequired)) {throw ('Missing existing tool: '+$taskRequired)}
}
# A UI rewrite can remove nested classes/resources. Never ship stale generated
# files from an earlier source revision in the next APK.
foreach($taskGenerated in @('classes','generated','dex','tests')) {
    $taskGeneratedPath=Join-Path $taskBuild $taskGenerated
    if(Test-Path $taskGeneratedPath){Remove-Item -Recurse -Force $taskGeneratedPath}
    New-Item -ItemType Directory -Path $taskGeneratedPath | Out-Null
}
$taskXz=Join-Path $taskCache 'xz-1.12-sources.jar'
$taskXzSha='3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b'
$taskXzSourceSha='c35c682fa8b617c1f6f21270df14bad4e11df2fe46962dfe45e765b7aec0181b'
if(-not (Test-Path $taskXz)) {
    [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
    Write-Output 'Downloading only XZ for Java 1.12 into project cache'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/org/tukaani/xz/1.12/xz-1.12-sources.jar' -OutFile ($taskXz+'.part') -TimeoutSec 60
    Move-Item -LiteralPath ($taskXz+'.part') -Destination $taskXz
}
if((Get-FileHash $taskXz -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskXzSourceSha) {throw 'XZ source dependency checksum mismatch'}
[IO.File]::WriteAllText((Join-Path $taskArtifacts 'dependency.json'),('{"artifact":"org.tukaani:xz:1.12:sources","sha256":"'+$taskXzSourceSha+'","referenceBinarySha256":"'+$taskXzSha+'","license":"0BSD","source":"https://repo.maven.apache.org/maven2/org/tukaani/xz/1.12/xz-1.12-sources.jar","builtWith":"JDK15 --release 8"}'),[Text.UTF8Encoding]::new($false))

# Rebuild unchanged Java-8 sources with the existing JDK. The published jar has
# unnamed synthetic MethodParameters that crash this installed D8 version.
# No upstream scripts are run; Java-9+ multi-release variants are excluded.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskBaseJar=Join-Path $taskBuild 'xz-android-base.jar'
if(Test-Path $taskBaseJar) {Remove-Item $taskBaseJar}
$taskXzSourceRoot=Join-Path $taskBuild 'xz-sources'
$taskXzClasses=Join-Path $taskBuild 'xz-classes'
foreach($taskClean in @($taskXzSourceRoot,$taskXzClasses)) {
    if(Test-Path $taskClean) {Remove-Item -Recurse -Force $taskClean}
    New-Item -ItemType Directory -Path $taskClean | Out-Null
}
$taskInputZip=[IO.Compression.ZipFile]::OpenRead($taskXz)
try {
    foreach($taskEntry in $taskInputZip.Entries) {
        if($taskEntry.FullName -notmatch '^org/tukaani/xz/([A-Za-z0-9_]+/)*[A-Za-z0-9_]+\.java$') {continue}
        $taskTarget=Join-Path $taskXzSourceRoot $taskEntry.FullName
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($taskTarget)) -Force | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry,$taskTarget)
    }
} finally {$taskInputZip.Dispose()}
$taskXzSources=@(Get-ChildItem $taskXzSourceRoot -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
# javac argument files avoid the Windows command line length limit.
$taskXzArguments=Join-Path $taskBuild 'xz-sources.args'
$taskXzLines=@($taskXzSources | ForEach-Object {'"'+$_.Replace('\','/')+'"'})
[IO.File]::WriteAllLines($taskXzArguments,$taskXzLines,[Text.UTF8Encoding]::new($false))
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') @('-encoding','UTF-8','--release','8','-g','-d',$taskXzClasses,('@'+$taskXzArguments))
Invoke-TaskTool (Join-Path $Jdk 'bin\jar.exe') @('cf',$taskBaseJar,'-C',$taskXzClasses,'.')

$taskSourceRoot=Join-Path $taskRoot 'app\src\main\java'
$taskStubRoot=Join-Path $taskRoot 'compile-stubs'
$taskStubClasses=Join-Path $taskBuild 'compile-stubs'
New-Item -ItemType Directory -Path $taskStubClasses -Force | Out-Null
$taskStubSources=@(Get-ChildItem $taskStubRoot -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') (@('-encoding','UTF-8','--release','8','-d',$taskStubClasses)+$taskStubSources)
$taskTarSource=Join-Path $taskSourceRoot 'org\factorio\local\launcher\SafeTarReader.java'
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') @('-encoding','UTF-8','--release','8','-d',(Join-Path $taskBuild 'tests'),$taskTarSource,(Join-Path $taskRoot 'tests\ArchiveTests.java'))
Invoke-TaskTool (Join-Path $Jdk 'bin\java.exe') @('-cp',(Join-Path $taskBuild 'tests'),'ArchiveTests')
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') @('-encoding','UTF-8','--release','8','-d',(Join-Path $taskBuild 'tests'),(Join-Path $taskRoot 'app\src\main\java\org\factorio\local\launcher\GameProfileConfig.java'),(Join-Path $taskRoot 'tests\GameProfileConfigTests.java'))
Invoke-TaskTool (Join-Path $Jdk 'bin\java.exe') @('-cp',(Join-Path $taskBuild 'tests'),'org.factorio.local.launcher.GameProfileConfigTests')
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') @('-encoding','UTF-8','--release','8','-d',(Join-Path $taskBuild 'tests'),(Join-Path $taskRoot 'app\src\main\java\org\factorio\local\launcher\LiveGraphics.java'),(Join-Path $taskRoot 'tests\LiveGraphicsTests.java'))
Invoke-TaskTool (Join-Path $Jdk 'bin\java.exe') @('-cp',(Join-Path $taskBuild 'tests'),'org.factorio.local.launcher.LiveGraphicsTests')
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') @('-encoding','UTF-8','--release','8','-d',(Join-Path $taskBuild 'tests'),(Join-Path $taskRoot 'app\src\main\java\com\termux\x11\TouchMouseState.java'),(Join-Path $taskRoot 'app\src\main\java\com\termux\x11\RenderSize.java'),(Join-Path $taskRoot 'tests\TouchMouseStateTests.java'))
Invoke-TaskTool (Join-Path $Jdk 'bin\java.exe') @('-cp',(Join-Path $taskBuild 'tests'),'com.termux.x11.TouchMouseStateTests')
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') @('-encoding','UTF-8','--release','8','-d',(Join-Path $taskBuild 'tests'),(Join-Path $taskSourceRoot 'org\factorio\local\launcher\GameFiles.java'),(Join-Path $taskRoot 'tests\GameFilesTests.java'))
Invoke-TaskTool (Join-Path $Jdk 'bin\java.exe') @('-cp',(Join-Path $taskBuild 'tests'),'org.factorio.local.launcher.GameFilesTests')
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') @('-encoding','UTF-8','--release','8','-d',(Join-Path $taskBuild 'tests'),(Join-Path $taskSourceRoot 'org\factorio\local\launcher\LanguagePolicy.java'),(Join-Path $taskRoot 'tests\LanguagePolicyTests.java'))
Invoke-TaskTool (Join-Path $Jdk 'bin\java.exe') @('-cp',(Join-Path $taskBuild 'tests'),'org.factorio.local.launcher.LanguagePolicyTests')

Invoke-TaskTool (Join-Path $taskTools 'aapt2.exe') @('compile','--dir',(Join-Path $taskRoot 'app\src\main\res'),'-o',(Join-Path $taskBuild 'resources.zip'))
# Application id lives in the manifest. Java and R stay in the original source
# package, so one dex can be packed into the normal and performance ids.
$taskJavaPackage='org.factorio.local.launcher'
$taskAppPackage='dev.plutocrown.factorio'
$taskPerfPackage='com.miHoYo.Yuanshen'
$taskManifest=Join-Path $taskRoot 'app\src\main\AndroidManifest.xml'
$taskManifestText=[IO.File]::ReadAllText($taskManifest)
if($taskManifestText -notmatch 'package="dev\.plutocrown\.factorio"') {throw 'Manifest package must be dev.plutocrown.factorio'}
if($taskManifestText -notmatch 'android:versionName="([0-9]+\.[0-9]+\.[0-9]+)') {throw 'Version name must start with a numeric version'}
$taskVersionFile=$Matches[1]
$taskUnsigned=Join-Path $taskBuild 'unsigned.apk'
Invoke-TaskTool (Join-Path $taskTools 'aapt2.exe') @('link','-o',$taskUnsigned,'-I',$taskAndroid,'--manifest',$taskManifest,'--custom-package',$taskJavaPackage,'--java',(Join-Path $taskBuild 'generated'),'-A',(Join-Path $taskRoot 'app\src\main\assets'),(Join-Path $taskBuild 'resources.zip'))
$taskSources=@(Get-ChildItem -LiteralPath $taskSourceRoot -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
$taskSources+=@(Get-ChildItem -LiteralPath (Join-Path $taskBuild 'generated') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
# Use JDK Java-8 compiler APIs for lambda lowering; android.jar intentionally
# omits LambdaMetafactory's compiler-only methods. D8 desugars the bytecode.
$taskCompile=@('-encoding','UTF-8','--release','8','-classpath',($taskAndroid+';'+$taskBaseJar+';'+$taskStubClasses),'-d',(Join-Path $taskBuild 'classes'))+$taskSources
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') $taskCompile
# Run the real importer with a host-only Context stub and a separate JVM
# holding the live-session file lease. These stub classes never enter the APK.
$taskImportTests=Join-Path $taskBuild 'import-tests'
if(Test-Path $taskImportTests){Remove-Item -Recurse -Force $taskImportTests}
New-Item -ItemType Directory -Path $taskImportTests | Out-Null
$taskImportClassPath=$taskImportTests+';'+(Join-Path $taskBuild 'classes')+';'+$taskAndroid+';'+$taskBaseJar
Invoke-TaskTool (Join-Path $Jdk 'bin\javac.exe') @('-encoding','UTF-8','--release','8','-classpath',$taskImportClassPath,'-d',$taskImportTests,(Join-Path $taskRoot 'tests\host-stubs\android\content\Context.java'),(Join-Path $taskRoot 'tests\ImportLeaseTests.java'))
Invoke-TaskTool (Join-Path $Jdk 'bin\java.exe') @('-cp',$taskImportClassPath,'org.factorio.local.launcher.ImportLeaseTests')
$taskClasses=Join-Path $taskBuild 'classes.jar'
Invoke-TaskTool (Join-Path $Jdk 'bin\jar.exe') @('cf',$taskClasses,'-C',(Join-Path $taskBuild 'classes'),'.')
# Older installed d8.bat uses java.ext.dirs, removed in JDK 9. Invoke the
# same installed compiler jar directly without modifying the SDK.
Invoke-TaskTool (Join-Path $Jdk 'bin\java.exe') @('-cp',(Join-Path $taskTools 'lib\d8.jar'),'com.android.tools.r8.D8','--release','--min-api','26','--lib',$taskAndroid,'--output',(Join-Path $taskBuild 'dex'),$taskClasses,$taskBaseJar)
$script:taskNativeRoot=Join-Path $taskBuild 'native'
$taskNativeLib=Join-Path $script:taskNativeRoot 'lib'
if(Test-Path $script:taskNativeRoot) {Remove-Item -Recurse -Force $script:taskNativeRoot}
New-Item -ItemType Directory -Path $taskNativeLib -Force | Out-Null
$script:taskHasNative=$false
$taskJni=Join-Path $taskRoot 'app\src\main\jniLibs'
if(Test-Path -LiteralPath $taskJni) {
    $taskJniFiles=@(Get-ChildItem -LiteralPath $taskJni -Recurse -File)
    if($taskJniFiles.Count -gt 0) {
        Copy-Item -Path (Join-Path $taskJni '*') -Destination $taskNativeLib -Recurse
        $script:taskHasNative=$true
    }
}
if(-not $script:taskHasNative) {Write-Output 'No jniLibs; APK will not contain the pinned native runtime'}
$taskKey=Join-Path $taskLocal 'release.keystore'
$taskPassFile=Join-Path $taskLocal 'release-keystore.pass'
$script:taskKeyAlias='factoriorelease'
if(Test-Path -LiteralPath $taskPassFile) {
    $script:taskStorePass=([IO.File]::ReadAllText($taskPassFile)).Trim()
} else {
    $taskBytes=New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($taskBytes)
    $script:taskStorePass=[Convert]::ToBase64String($taskBytes)
    [IO.File]::WriteAllText($taskPassFile,$script:taskStorePass,[Text.UTF8Encoding]::new($false))
}
$script:taskKeyPass=$script:taskStorePass
if(-not (Test-Path $taskKey)) {
    Invoke-TaskTool (Join-Path $Jdk 'bin\keytool.exe') @('-genkeypair','-noprompt','-keystore',$taskKey,'-alias',$script:taskKeyAlias,'-storepass',$script:taskStorePass,'-keypass',$script:taskKeyPass,'-dname','CN=Factorio Local Release','-keyalg','RSA','-keysize','2048','-validity','10000')
}
function Complete-LauncherApk([string]$unsigned,[string]$apkName,[string]$packageName,[bool]$performance) {
    $payload=Join-Path $taskBuild ($packageName+'.unsigned.apk')
    Copy-Item -LiteralPath $unsigned -Destination $payload -Force
    foreach($dexFile in @(Get-ChildItem -LiteralPath (Join-Path $taskBuild 'dex') -Filter 'classes*.dex')) {
        Invoke-TaskTool (Join-Path $Jdk 'bin\jar.exe') @('uf',$payload,'-C',$dexFile.DirectoryName,$dexFile.Name)
    }
    if($script:taskHasNative) {
        Invoke-TaskTool (Join-Path $Jdk 'bin\jar.exe') @('uf',$payload,'-C',$script:taskNativeRoot,'lib')
    }
    $aligned=Join-Path $taskBuild ($packageName+'.aligned.apk')
    Invoke-TaskTool (Join-Path $taskTools 'zipalign.exe') @('-f','4',$payload,$aligned)
    $apk=Join-Path $taskArtifacts $apkName
    Invoke-TaskTool (Join-Path $taskTools 'apksigner.bat') @('sign','--ks',$taskKey,'--ks-key-alias',$script:taskKeyAlias,'--ks-pass',('pass:'+$script:taskStorePass),'--key-pass',('pass:'+$script:taskKeyPass),'--out',$apk,$aligned)
    Invoke-TaskTool (Join-Path $taskTools 'apksigner.bat') @('verify','--verbose','--print-certs',$apk)
    Invoke-TaskTool (Join-Path $taskTools 'zipalign.exe') @('-c','4',$apk)
    $badgeLines=& (Join-Path $taskTools 'aapt2.exe') @('dump','badging',$apk)
    if($LASTEXITCODE -ne 0) {throw 'aapt2 dump badging failed'}
    $badge=$badgeLines -join "`n"
    Write-Host $badge
    if($badge -notmatch ("package: name='"+[regex]::Escape($packageName)+"'")) {throw ('Application id mismatch for '+$packageName)}
    if($badge -notmatch "launchable-activity: name='org\.factorio\.local\.launcher\.MainActivity'") {throw 'Launcher activity was rewritten with the application id'}
    if($badge -match 'application-debuggable') {throw 'Release APK is still debuggable'}
    [pscustomobject]@{Apk=$apk;Bytes=(Get-Item $apk).Length;Sha256=(Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant();Package=$packageName;PerformancePackage=$performance;Version='0.2.0-runtime-probe'}
}
$taskPackages=@(Complete-LauncherApk $taskUnsigned ('FactorioLauncher-'+$taskVersionFile+'.apk') $taskAppPackage $false)
if($PerformancePackage) {
    $taskPerfUnsigned=Join-Path $taskBuild 'unsigned-performance.apk'
    $taskPerfJava=Join-Path $taskBuild 'generated-performance'
    if(Test-Path $taskPerfJava) {Remove-Item -Recurse -Force $taskPerfJava}
    New-Item -ItemType Directory -Path $taskPerfJava | Out-Null
    Invoke-TaskTool (Join-Path $taskTools 'aapt2.exe') @('link','-o',$taskPerfUnsigned,'-I',$taskAndroid,'--manifest',$taskManifest,'--custom-package',$taskJavaPackage,'--rename-manifest-package',$taskPerfPackage,'--rename-resources-package',$taskPerfPackage,'--java',$taskPerfJava,'-A',(Join-Path $taskRoot 'app\src\main\assets'),(Join-Path $taskBuild 'resources.zip'))
    $taskDefaultR=Join-Path $taskBuild 'generated\org\factorio\local\launcher\R.java'
    $taskPerfR=Join-Path $taskPerfJava 'org\factorio\local\launcher\R.java'
    if((Get-FileHash -LiteralPath $taskDefaultR -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $taskPerfR -Algorithm SHA256).Hash) {
        throw 'Performance package changed R.java; the compiled dex cannot be reused'
    }
    $taskPackages += @(Complete-LauncherApk $taskPerfUnsigned ('FactorioLauncher-'+$taskVersionFile+'-YuanShen-sign.apk') $taskPerfPackage $true)
}
$taskExpectedPackages=if($PerformancePackage){2}else{1}
if($taskPackages.Count -ne $taskExpectedPackages){throw 'Unexpected APK result count; build logs must not enter package metadata'}
foreach($taskPackage in $taskPackages){
    if(-not $taskPackage.Apk -or $taskPackage.Sha256 -notmatch '^[a-f0-9]{64}$'){throw 'Invalid APK result metadata'}
}
$taskResult=[pscustomobject]@{Version='0.2.0-runtime-probe';JavaPackage=$taskJavaPackage;Sdk=$Sdk;Jdk=$Jdk;BuildTools='33.0.1';BuildType='release';NativeLibraries=[bool]$script:taskHasNative;DeviceInstalled=$false;GameLaunched=$false;DependencySourceSha256=$taskXzSourceSha;DependencyBuiltJarSha256=(Get-FileHash $taskBaseJar -Algorithm SHA256).Hash.ToLowerInvariant();Packages=$taskPackages}
$taskResultJson=$taskResult | ConvertTo-Json -Compress -Depth 4
[IO.File]::WriteAllText((Join-Path $taskArtifacts 'build-result.json'),$taskResultJson,[Text.UTF8Encoding]::new($false))
Write-Output ('__FACTORIO_REPORT__'+$taskResultJson)
