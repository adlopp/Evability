# Compila la app y publica el APK como una nueva "release" en GitHub (adlopp/Evability).
# Uso:  .\publicar.ps1 -Notas "Qué ha cambiado"
# La etiqueta de la release sale de versionCode (app/build.gradle.kts): versionCode 3 -> v3.
# Antes de publicar una versión nueva hay que subir versionCode (y versionName) en app/build.gradle.kts.
param([string]$Notas = "Mejoras y correcciones")

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$gradle = Get-Content "app\build.gradle.kts" -Raw
$code = [regex]::Match($gradle, 'versionCode = (\d+)').Groups[1].Value
$name = [regex]::Match($gradle, 'versionName = "([^"]+)"').Groups[1].Value

$env:JAVA_HOME = (Get-ChildItem ".tools\jdk" -Directory)[0].FullName
$env:ANDROID_HOME = (Resolve-Path ".tools\sdk").Path

Write-Host "Compilando Evability $name (versionCode $code)..."
& .\gradlew.bat assembleDebug -q
if ($LASTEXITCODE -ne 0) { throw "La compilación ha fallado" }

Copy-Item "app\build\outputs\apk\debug\app-debug.apk" "Evability.apk" -Force

Write-Host "Publicando v$code en GitHub..."
gh release create "v$code" "Evability.apk" --repo adlopp/Evability --title "Evability $name" --notes $Notas
if ($LASTEXITCODE -ne 0) { throw "No se pudo crear la release (¿ya existe v$code?)" }

Write-Host "Listo: https://github.com/adlopp/Evability/releases/tag/v$code"
