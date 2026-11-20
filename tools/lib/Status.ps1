function Show-Status {
    Write-Title 'Durum'
    $brand = Get-Prop 'ro.product.manufacturer'
    $model = Get-Prop 'ro.product.model'
    $android = Get-Prop 'ro.build.version.release'
    $memory = Get-MemoryMB
    $storage = Get-FreeStorageMB

    Write-Info "Cihaz       : $model ($brand) · Android $android"
    Write-Info "Bağlantı    : $script:Serial"
    Write-Info ("RAM         : {0} MB boş / {1} MB" -f $memory.Available, $memory.Total)
    if ($storage -ge 0) { Write-Info "Depolama    : $storage MB boş" }

    $currentHome = (Invoke-Shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' -AllowFail) -split "`n" | Select-Object -Last 1
    Write-Info "Ana ekran   : $($currentHome.Trim())"

    $version = [regex]::Match((Invoke-Shell "dumpsys package $script:LauncherPackage" -AllowFail), 'versionName=(\S+)').Groups[1].Value
    if ($version) { Write-Info "muffuqOS    : $version kurulu" } else { Write-Info 'muffuqOS    : kurulu değil' }

    $animation = (Invoke-Shell 'settings get global window_animation_scale').Trim()
    Write-Info "Animasyon   : $animation"

    $state = Get-DeviceState
    Write-Info ("Kapatılan   : {0} paket + {1} stok launcher paketi" -f $state.disabled.Count, $state.launcherDisabled.Count)

    Write-Host ''
    Write-Info 'En çok RAM kullananlar:'
    $meminfo = Invoke-Shell 'dumpsys meminfo' -AllowFail
    $section = $meminfo -split 'Total PSS by process:' | Select-Object -Skip 1 -First 1
    if ($section) {
        ($section -split "`n") | Where-Object { $_ -match '^\s*[\d,]+K:' } | Select-Object -First 6 | ForEach-Object {
            Write-Info ('  ' + $_.Trim())
        }
    }
}
