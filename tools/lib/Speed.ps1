# Debloat + tweaks. Only ever disables (never uninstalls) so every change is one `pm enable` away from undone.

$script:LevelsByPreset = @{
    safe        = @('safe')
    recommended = @('safe', 'apps')
    aggressive  = @('safe', 'apps', 'aggressive')
}

$script:LevelLabels = @{
    safe       = 'güvenli'
    apps       = 'uygulama'
    optional   = 'isteğe bağlı'
    aggressive = 'agresif'
    vendor     = 'üretici'
}

# Preinstalled apps the vendor scan must never offer: live TV / HDMI inputs, store, settings, keyboards, launchers.
$script:ProtectedPattern = '^(com\.android\.vending|com\.android\.tv\.settings|com\.google\.android\.gms|com\.google\.android\.gsf|com\.muffuq\.os)$|tvcenter|tvinput|\.tv\.service|setupwizard|inputmethod|launcher|mediaplayer|settings'

function Get-DebloatList {
    $path = Join-Path $script:ProjectRoot 'debloat\packages.txt'
    foreach ($raw in (Get-Content $path -Encoding UTF8)) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $parts = $line -split '\|', 3
        if ($parts.Count -lt 2) { continue }
        [pscustomobject]@{
            Level       = $parts[0].Trim().ToLowerInvariant()
            Package     = $parts[1].Trim()
            Description = if ($parts.Count -gt 2) { $parts[2].Trim() } else { '' }
            Selected    = $false
        }
    }
}

<# Launchable preinstalled apps we know nothing about. Offered unticked so the user can decide. #>
function Get-VendorCandidates($Known) {
    $system = Get-PackageSet '-s -e'
    $launchable = Invoke-Shell 'cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER' -AllowFail
    $seen = @{}
    foreach ($line in ($launchable -split "`n")) {
        if ($line -notmatch '^([\w.]+)/') { continue }
        $package = $Matches[1]
        if ($seen.ContainsKey($package) -or -not $system.Contains($package)) { continue }
        if ($Known -contains $package -or $package -match $script:ProtectedPattern) { continue }
        if ($package -match '^com\.(google|android)\.') { continue }
        $seen[$package] = $true
        [pscustomobject]@{
            Level       = 'vendor'
            Package     = $package
            Description = "Üretici uygulaması, ne olduğunu biliyorsan kapat"
            Selected    = $false
        }
    }
}

function Show-PackagePicker($Items) {
    while ($true) {
        Write-Host ''
        for ($i = 0; $i -lt $Items.Count; $i++) {
            $item = $Items[$i]
            $mark = if ($item.Selected) { '[x]' } else { '[ ]' }
            $color = if ($item.Selected) { 'White' } else { 'DarkGray' }
            $label = $script:LevelLabels[$item.Level]
            Write-Host ("  {0,3}. {1} {2,-13} {3}" -f ($i + 1), $mark, $label, $item.Description) -ForegroundColor $color
            Write-Host ("                          {0}" -f $item.Package) -ForegroundColor DarkGray
        }
        Write-Host ''
        Write-Info 'Numara yaz (ör. 3 7 12) -> işaretini değiştir   |   Enter -> devam   |   q -> vazgeç'
        $answer = (Read-Host '  ?').Trim().ToLowerInvariant()
        if (-not $answer) { return $Items }
        if ($answer -eq 'q') { return $null }
        foreach ($token in ($answer -split '[\s,]+')) {
            $n = 0
            if ([int]::TryParse($token, [ref]$n) -and $n -ge 1 -and $n -le $Items.Count) {
                $Items[$n - 1].Selected = -not $Items[$n - 1].Selected
            }
        }
    }
}

function Invoke-Speed {
    param(
        [ValidateSet('safe', 'recommended', 'aggressive')][string]$Level = 'recommended',
        [string]$Animation = '0.5'
    )
    Write-Title 'Hızlandırma'
    $state = Get-DeviceState
    $before = Get-MemoryMB

    $enabled = Get-PackageSet '-e'
    $known = @(Get-DebloatList)
    $items = @($known | Where-Object { $enabled.Contains($_.Package) })
    $levels = $script:LevelsByPreset[$Level]
    foreach ($item in $items) { $item.Selected = $levels -contains $item.Level }
    if (-not $script:AssumeYes) {
        $items += @(Get-VendorCandidates ($known | ForEach-Object Package))
    }

    if ($items.Count -eq 0) {
        Write-Ok 'Kapatılacak bir şey kalmamış, liste temiz.'
    } else {
        if (-not $script:AssumeYes) {
            Write-Info "Bu TV'de bulunanlar. '$Level' ayarına göre işaretlendi:"
            $items = Show-PackagePicker $items
            if ($null -eq $items) { Write-Warn 'Vazgeçildi.'; return }
        }
        foreach ($item in ($items | Where-Object Selected)) {
            if (Disable-TrackedPackage $state $item.Package) {
                Write-Ok "$($item.Description)  ($($item.Package))"
            } else {
                Write-Fail "Kapatılamadı: $($item.Package)"
            }
        }
    }

    Write-Step "Animasyonlar $Animation hızına alınıyor..."
    foreach ($key in @('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')) {
        Set-TrackedSetting $state 'global' $key $Animation
    }
    Write-Ok 'Menüler ve geçişler daha çevik.'

    Write-Step 'Uygulama önbellekleri temizleniyor ve arka plan süreçleri kapatılıyor...'
    $storageBefore = Get-FreeStorageMB
    Invoke-Shell 'pm trim-caches 64G' -AllowFail | Out-Null
    Invoke-Shell 'am kill-all' -AllowFail | Out-Null
    $storageAfter = Get-FreeStorageMB
    if ($storageBefore -ge 0 -and $storageAfter -ge 0) {
        Write-Ok ("Depolama: {0} MB boştu, şimdi {1} MB boş." -f $storageBefore, $storageAfter)
    }

    Start-Sleep -Seconds 3
    $after = Get-MemoryMB
    Write-Ok ("Boş RAM: {0} MB -> {1} MB (toplam {2} MB)" -f $before.Available, $after.Available, $after.Total)
}

function Get-FreeStorageMB {
    $line = (Invoke-Shell 'df -k /data' -AllowFail) -split "`n" | Select-Object -Last 1
    $columns = $line -split '\s+'
    if ($columns.Count -ge 4 -and $columns[3] -match '^\d+$') { return [int]([long]$columns[3] / 1024) }
    return -1
}
