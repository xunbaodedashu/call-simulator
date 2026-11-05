# 一键重新编译并签名 模拟来电_UCN_MQ_Z.apk
$ErrorActionPreference = "Stop"
$rootDir = Split-Path -Parent $PSScriptRoot
Set-Location $rootDir

Copy-Item -Path "模拟来电_网页预览版.html" -Destination "android-project/app/src/main/assets/index.html" -Force

$sdkDir = "$env:USERPROFILE/.android-sdk-mini"
$btDir = "$sdkDir/build-tools/android-14"
$aapt2 = "$btDir/aapt2.exe"
$d8Jar = "$btDir/lib/d8.jar"
$zipalign = "$btDir/zipalign.exe"
$apksignerJar = "$btDir/lib/apksigner.jar"
$androidJar = "$sdkDir/platform/android-34/android.jar"

$buildDir = "android-project/build-out"
if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path "$buildDir/compiled_res", "$buildDir/gen", "$buildDir/classes", "$buildDir/dex" | Out-Null

& $aapt2 compile --dir "android-project/app/src/main/res" -o "$buildDir/compiled_res/res.zip"
$unsignedApk = "$buildDir/unsigned.apk"
& $aapt2 link -o $unsignedApk -I $androidJar --manifest "android-project/app/src/main/AndroidManifest.xml" -A "android-project/app/src/main/assets" --java "$buildDir/gen" "$buildDir/compiled_res/res.zip"

$javaFiles = Get-ChildItem -Path "android-project/app/src/main/java", "$buildDir/gen" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -g:lines,source --release 11 -cp $androidJar -d "$buildDir/classes" $javaFiles

$classesJar = "$buildDir/classes.jar"
& jar cf $classesJar -C "$buildDir/classes" .
& java -cp $d8Jar com.android.tools.r8.D8 --lib $androidJar --min-api 24 --output "$buildDir/dex" $classesJar

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Resolve-Path $unsignedApk).Path, [System.IO.Compression.ZipArchiveMode]::Update)
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Resolve-Path "$buildDir/dex/classes.dex").Path, "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

$alignedApk = "$buildDir/aligned.apk"
& $zipalign -f -p 4 $unsignedApk $alignedApk

$keystore = "$buildDir/release.jks"
& keytool -genkeypair -v -keystore $keystore -alias ucnmqz -keyalg RSA -keysize 2048 -validity 10000 -storepass 123456 -keypass 123456 -dname "CN=UCN MQ Z, OU=Studio, O=UCN, L=Beijing, ST=Beijing, C=CN"

$finalApk = "模拟来电_UCN_MQ_Z.apk"
& java -jar $apksignerJar sign --ks $keystore --ks-key-alias ucnmqz --ks-pass pass:123456 --key-pass pass:123456 --out $finalApk $alignedApk
Remove-Item -Recurse -Force $buildDir
Write-Host "APK 编译签名完成：$finalApk"
