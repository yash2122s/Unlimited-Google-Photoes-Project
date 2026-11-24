param([string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (!(Test-Path (Join-Path $Jdk 'bin/jpackage.exe'))) { throw 'Set JAVA_HOME to a full JDK 21 with jpackage.' }
    $env:JAVA_HOME = $Jdk
    & .\gradlew.bat :windows:installDist
    if ($LASTEXITCODE) { throw 'Windows compilation failed' }
    $stage = Join-Path $PSScriptRoot ('windows/build/package-' + [Guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $stage -Force
    & "$Jdk/bin/jpackage.exe" --type app-image --name Lantern --app-version 1.0 --dest $stage --input windows/build/install/windows/lib --main-jar windows.jar --main-class dev.lantern.windows.MainKt --add-modules java.desktop,java.logging,java.management,java.naming,java.net.http,jdk.crypto.ec,jdk.unsupported,jdk.accessibility,java.security.jgss --java-options '--enable-native-access=ALL-UNNAMED'
    if ($LASTEXITCODE) { throw 'Runtime packaging failed' }
    $out = New-Item -ItemType Directory -Force dist
    & tar.exe -a -cf (Join-Path $out.FullName 'Lantern-Windows-x64.zip') -C $stage Lantern
    if ($LASTEXITCODE) { throw 'ZIP creation failed' }
    Write-Output (Join-Path $stage 'Lantern/Lantern.exe')
} finally { Pop-Location }

