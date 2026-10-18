# Signs the release APK so it installs as an update over the original debug-signed Totem.
#
# Totem was first installed signed with this PC's debug key. Android only accepts updates from
# the same key, unless the APK carries an APK Signature Scheme v3 "rotation" proof that the old
# key handed over to a new one. This script creates that proof once (~/.secrets/totem-lineage.bin)
# and then signs every release with: old key (v1/v2 blocks) + new key with the lineage (v3 block).
#
# Needs in ~/.secrets: totem-release.jks + totem-release.properties, and the original debug key,
# copied the first time to totem-original-debug.keystore. BACK UP ALL OF ~/.secrets.
#
#   ./gradlew :app:assembleRelease   (in android/)
#   powershell -File scripts/sign-android.ps1
param(
    [string]$InputApk = "$PSScriptRoot\..\android\app\build\outputs\apk\release\app-release.apk"
)
$ErrorActionPreference = "Stop"
$sec = Join-Path $HOME ".secrets"
$env:PATH = "$env:LOCALAPPDATA\Programs\jdk21\bin;$env:PATH"
$apksigner = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\apksigner.bat" | Sort-Object FullName | Select-Object -Last 1

$props = @{}
Get-Content (Join-Path $sec "totem-release.properties") | Where-Object { $_ -match "^\s*([^#=]+)=(.*)$" } | ForEach-Object { $props[$Matches[1].Trim()] = $Matches[2] }
$env:TOTEM_KS_PASS = $props["storePassword"]

# Keep a copy of the original debug key next to the release key: it's part of the chain now.
$oldKs = Join-Path $sec "totem-original-debug.keystore"
if (-not (Test-Path $oldKs)) { Copy-Item (Join-Path $HOME ".android\debug.keystore") $oldKs; "saved $oldKs" }

$lineage = Join-Path $sec "totem-lineage.bin"
if (-not (Test-Path $lineage)) {
    & $apksigner.FullName rotate --out $lineage `
        --old-signer --ks $oldKs --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android `
        --new-signer --ks $props["storeFile"] --ks-key-alias $props["keyAlias"] --ks-pass env:TOTEM_KS_PASS --key-pass env:TOTEM_KS_PASS
    if ($LASTEXITCODE -ne 0) { throw "creating the rotation lineage failed" }
    "created $lineage"
}

$version = (Select-String -Path "$PSScriptRoot\..\android\app\build.gradle.kts" -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
$out = Join-Path (Split-Path $InputApk) "totem-$version.apk"
& $apksigner.FullName sign `
    --ks $oldKs --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android `
    --next-signer --ks $props["storeFile"] --ks-key-alias $props["keyAlias"] --ks-pass env:TOTEM_KS_PASS --key-pass env:TOTEM_KS_PASS `
    --lineage $lineage --rotation-min-sdk-version 28 `
    --out $out $InputApk
if ($LASTEXITCODE -ne 0) { throw "signing failed" }
Remove-Item Env:TOTEM_KS_PASS

& $apksigner.FullName verify --verbose --print-certs --min-sdk-version 28 $out | Select-String "Verified using|Signer|SHA-256|lineage|rotation|DN:"
"signed: $out"
