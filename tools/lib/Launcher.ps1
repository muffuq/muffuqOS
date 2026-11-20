# Installing muffuqOS and making it the Home screen.

$script:LauncherPackage = 'com.muffuq.os'
$script:LauncherActivity = 'com.muffuq.os/.MainActivity'

# Only exist to feed the stock launcher; they go away with it and come back with it.
$script:StockLauncherCompanions = @(
    'com.google.android.tvrecommendations',
    'com.google.android.leanbacklauncher.recommendations',
    'com.smartdevice.leanbacklauncher.customizer'
)

# HOME activities the system itself needs (boot, first-run setup). Never disabled.
$script:SystemHomePattern = '^(com\.android\.tv\.settings|com\.android\.settings|com\.android\.provision|com\.android\.tv\.provision)$'

function Get-HomeActivities {
    $output = Invoke-Shell 'cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME' -AllowFail
    return @(($output -split "`n") | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[\w.]+/[\w.$]+$' })
}

function Find-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    foreach ($candidate in @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:ProgramFiles\Android\Android Studio\jre")) {
        if (Test-Path (Join-Path $candidate 'bin\java.exe')) { return $candidate }
    }
    return $null
}

function Resolve-LauncherApk([string]$Apk) {
    if ($Apk) {
        if (-not (Test-Path $Apk)) { throw "APK bulunamadı: $Apk" }
        return (Resolve-Path $Apk).Path
    }
    $default = Join-Path $script:ProjectRoot 'dist\muffuqOS-launcher.apk'
    if (Test-Path $default) { return $default }

    Write-Warn 'Derlenmiş launcher bulunamadı (dist\muffuqOS-launcher.apk).'
    $java = Find-Java
    if (-not $java) { throw 'Derlemek için Java gerekli (Android Studio kurulu olmalı) ya da -Apk ile hazır bir APK ver.' }
    if (-not (Read-YesNo 'Şimdi derleyeyim mi? (ilk seferde birkaç dakika sürer)')) { throw 'APK olmadan devam edilemez.' }
    $env:JAVA_HOME = $java
    $gradlew = Join-Path $script:ProjectRoot 'launcher\gradlew.bat'
    Push-Location (Join-Path $script:ProjectRoot 'launcher')
    try { & $gradlew dist --console=plain } finally { Pop-Location }
    if (-not (Test-Path $default)) { throw 'Derleme başarısız oldu.' }
    return $default
}

function Install-Launcher {
    param([string]$Apk, [switch]$KeepStock)
    Write-Title 'muffuqOS launcher'
    $state = Get-DeviceState
    $apkPath = Resolve-LauncherApk $Apk

    Write-Step 'Launcher TV''ye yükleniyor...'
    $output = Invoke-Adb -Arguments @('install', '-r', $apkPath) -AllowFail
    if ($output -notmatch 'Success') {
        if ($output -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
            throw "TV'deki muffuqOS başka bir bilgisayarda imzalanmış. Önce 'Geri al' ile kaldırıp tekrar dene."
        }
        throw "Yükleme başarısız: $output"
    }
    Write-Ok 'Yüklendi.'

    # Never take the stock launcher away before we've seen ours actually come up. Wake the TV first:
    # with a screensaver in front our activity would never reach the resumed state.
    Invoke-Shell 'input keyevent KEYCODE_WAKEUP' -AllowFail | Out-Null
    Invoke-Shell "am start -n $script:LauncherActivity" -AllowFail | Out-Null
    if (-not (Wait-Resumed $script:LauncherPackage)) {
        throw "muffuqOS açılmadı; stok launcher'a dokunulmadı."
    }
    Write-Ok 'muffuqOS açıldı.'

    Invoke-Shell "cmd package set-home-activity $script:LauncherActivity" -AllowFail | Out-Null
    if ((Get-SdkLevel) -ge 29) {
        Invoke-Shell "cmd role add-role-holder --user 0 android.app.role.HOME $script:LauncherPackage" -AllowFail | Out-Null
    }

    if ($KeepStock) {
        Write-Info 'Stok launcher açık bırakıldı (-KeepStockLauncher).'
    } else {
        $enabled = Get-PackageSet '-e'
        $stock = @(Get-HomeActivities | ForEach-Object { ($_ -split '/')[0] } |
            Where-Object { $_ -ne $script:LauncherPackage -and $_ -notmatch $script:SystemHomePattern } |
            Select-Object -Unique)
        $targets = @($stock) + @($script:StockLauncherCompanions | Where-Object { $enabled.Contains($_) })
        foreach ($package in $targets) {
            if (Disable-TrackedPackage $state $package 'launcherDisabled') {
                Write-Ok "Kapatıldı: $package"
            } else {
                Write-Warn "Kapatılamadı: $package"
            }
        }
    }

    # AirPlay: the mirroring screen has to be able to come up over whatever app is on screen.
    Invoke-Shell "appops set $script:LauncherPackage SYSTEM_ALERT_WINDOW allow" -AllowFail | Out-Null
    # muffuqOS screensaver. The previous one is remembered, so 'Geri al' brings it back.
    Set-TrackedSetting $state 'secure' 'screensaver_components' "$script:LauncherPackage/.dream.MuffuqDream"
    Write-Ok 'iPhone/iPad yansıtma (AirPlay) hazır, ekran koruyucu muffuqOS yapıldı.'
    # Sahne: Spotify'ın çalan şarkısını görebilmek (bildirim dinleyici) ve ritmi duyabilmek (ses görselleştirici).
    Invoke-Shell "cmd notification allow_listener $script:LauncherPackage/$script:LauncherPackage.stage.MediaListener" -AllowFail | Out-Null
    Invoke-Shell "pm grant $script:LauncherPackage android.permission.RECORD_AUDIO" -AllowFail | Out-Null
    Write-Ok 'Sahne (şarkı sözleri + ışık) Spotify ile senkron çalışmaya hazır.'
    # Ahead-of-time compile: otherwise the first seconds of every screen run interpreted while the JIT warms up,
    # which on this TV's CPU means stutter.
    Write-Step 'Launcher önceden derleniyor (akıcılık için)...'
    Invoke-Shell "cmd package compile -m speed -f $script:LauncherPackage" -AllowFail | Out-Null

    Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
    if (Wait-Resumed $script:LauncherPackage) {
        Write-Ok "Kumandanın Home tuşu artık muffuqOS'u açıyor."
    } else {
        Write-Warn "Home tuşu henüz muffuqOS'a gitmiyor. TV'yi yeniden başlatmak genelde yeter; olmazsa 'Geri al'."
    }
}
