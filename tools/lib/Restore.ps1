# Puts the TV back the way it was before muffuqOS, using the record in backups/.

function Invoke-Restore {
    param([switch]$UninstallLauncher)
    Write-Title 'Geri al'
    $state = Get-DeviceState
    if ((Test-StateEmpty $state) -and -not $UninstallLauncher) {
        Write-Ok "Bu TV'de muffuqOS'un değiştirdiği bir şey kayıtlı değil."
        return
    }

    # Stock launcher first: the moment it's back, Home has somewhere to go even if the rest fails.
    foreach ($list in @('launcherDisabled', 'disabled')) {
        foreach ($package in @($state.$list)) {
            $output = Invoke-Shell "pm enable $package" -AllowFail
            if ($output -match 'enabled') {
                Write-Ok "Geri açıldı: $package"
                $state.$list = @($state.$list | Where-Object { $_ -ne $package })
                Save-DeviceState $state
            } else {
                Write-Fail "Açılamadı: $package  ($output)"
            }
        }
    }

    foreach ($setting in @($state.settings)) {
        if ($setting.value -eq 'null' -or -not $setting.value) {
            Invoke-Shell "settings delete $($setting.namespace) $($setting.key)" -AllowFail | Out-Null
        } else {
            Invoke-Shell "settings put $($setting.namespace) $($setting.key) $($setting.value)" -AllowFail | Out-Null
        }
        Write-Ok "Ayar eski haline döndü: $($setting.key) = $($setting.value)"
    }
    $state.settings = @()
    Save-DeviceState $state

    $installed = (Get-PackageSet).Contains($script:LauncherPackage)
    if ($installed) {
        Invoke-Shell "appops set $script:LauncherPackage SYSTEM_ALERT_WINDOW default" -AllowFail | Out-Null
        Invoke-Shell "cmd notification disallow_listener $script:LauncherPackage/$script:LauncherPackage.stage.MediaListener" -AllowFail | Out-Null
        Invoke-Shell "pm revoke $script:LauncherPackage android.permission.RECORD_AUDIO" -AllowFail | Out-Null
        if ((Get-SdkLevel) -ge 29) {
            Invoke-Shell "cmd role remove-role-holder --user 0 android.app.role.HOME $script:LauncherPackage" -AllowFail | Out-Null
        }
        $stockHome = Get-HomeActivities | Where-Object { $_ -notmatch "^$([regex]::Escape($script:LauncherPackage))/" -and ($_ -split '/')[0] -notmatch $script:SystemHomePattern } | Select-Object -First 1
        if ($stockHome) { Invoke-Shell "cmd package set-home-activity $stockHome" -AllowFail | Out-Null }

        if ($UninstallLauncher -or (Read-YesNo 'muffuqOS launcher TV''den kaldırılsın mı?' $false)) {
            Invoke-Adb -Arguments @('uninstall', $script:LauncherPackage) -AllowFail | Out-Null
            Write-Ok 'muffuqOS launcher kaldırıldı.'
        }
    }

    Invoke-Shell 'input keyevent KEYCODE_HOME' -AllowFail | Out-Null
    Write-Ok 'TV eski haline döndü.'
}
