# Finding adb, picking the TV and running commands on it. Everything else talks to the TV through Invoke-Shell.

$script:Adb = $null
$script:Serial = $null
$script:PlatformToolsUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'

function Find-Adb {
    $candidates = @()
    $onPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($sdk) { $candidates += (Join-Path $sdk 'platform-tools\adb.exe') }
    }
    $candidates += (Join-Path $script:ToolsDir 'platform-tools\adb.exe')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

function Initialize-Adb {
    $script:Adb = Find-Adb
    if ($script:Adb) { return }

    Write-Warn 'Bu bilgisayarda adb bulunamadı.'
    if (-not (Read-YesNo "Google'ın resmi platform-tools paketi indirilsin mi? (~15 MB)")) {
        throw 'adb olmadan devam edilemez.'
    }
    $zip = Join-Path $env:TEMP 'muffuq-platform-tools.zip'
    Write-Step 'platform-tools indiriliyor...'
    Invoke-WebRequest -Uri $script:PlatformToolsUrl -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $script:ToolsDir -Force
    Remove-Item $zip
    $script:Adb = Join-Path $script:ToolsDir 'platform-tools\adb.exe'
    Write-Ok 'adb hazır.'
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$NoSerial,
        [switch]$AllowFail
    )
    $all = @()
    if ($script:Serial -and -not $NoSerial) { $all += @('-s', $script:Serial) }
    $all += $Arguments

    # adb writes progress to stderr; with 'Stop' PowerShell 5.1 would turn that into a terminating error.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $script:Adb @all 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
    $code = $LASTEXITCODE
    $text = (($output | ForEach-Object { "$_" }) -join "`n").Trim()
    if ($code -ne 0 -and -not $AllowFail) {
        throw "adb $($Arguments -join ' ') başarısız oldu: $text"
    }
    return $text
}

function Invoke-Shell([string]$Command, [switch]$AllowFail) {
    return Invoke-Adb -Arguments @('shell', $Command) -AllowFail:$AllowFail
}

function Get-Prop([string]$Name) {
    return (Invoke-Shell "getprop $Name").Trim()
}

function Get-AdbDevices {
    $lines = (Invoke-Adb -Arguments @('devices') -NoSerial) -split "`n" | Select-Object -Skip 1
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+(device|unauthorized|offline)') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    }
}

function Get-LastDeviceFile { return Join-Path $script:ToolsDir '.device' }

function Save-LastDevice([string]$Address) {
    Set-Content -Path (Get-LastDeviceFile) -Value $Address -Encoding UTF8
}

function Get-LastDevice {
    $file = Get-LastDeviceFile
    if (Test-Path $file) { return (Get-Content $file -Encoding UTF8 | Select-Object -First 1).Trim() }
    return $null
}

function Connect-Tv([string]$Address) {
    if ($Address -notmatch ':\d+$') { $Address = "${Address}:5555" }
    Write-Step "$Address adresine bağlanılıyor..."
    $output = Invoke-Adb -Arguments @('connect', $Address) -NoSerial -AllowFail
    if ($output -notmatch 'connected to') {
        Write-Fail "Bağlanılamadı: $output"
        Show-DevModeHelp
        return $null
    }

    # A fresh connection is 'unauthorized' until someone accepts the prompt on the TV.
    $warned = $false
    for ($i = 0; $i -lt 60; $i++) {
        $device = Get-AdbDevices | Where-Object Serial -eq $Address
        if ($device.State -eq 'device') {
            Save-LastDevice $Address
            Write-Ok 'Bağlandı.'
            return $Address
        }
        if ($device.State -eq 'unauthorized' -and -not $warned) {
            Write-Warn "TV ekranındaki 'USB hata ayıklamaya izin verilsin mi?' sorusunu onayla ('Her zaman izin ver' işaretli olsun)."
            $warned = $true
        }
        Start-Sleep -Seconds 1
    }
    Write-Fail 'TV bağlantıyı 60 saniye içinde onaylamadı.'
    return $null
}

function Connect-WithPairing {
    Write-Info "Android 11+ TV'lerde: Geliştirici seçenekleri > Kablosuz hata ayıklama > Eşleştirme koduyla eşle"
    $pairAddress = Read-Text 'Eşleştirme ekranındaki IP:port'
    $code = Read-Text '6 haneli eşleştirme kodu'
    if (-not $pairAddress -or -not $code) { return $null }
    $output = Invoke-Adb -Arguments @('pair', $pairAddress, $code) -NoSerial -AllowFail
    if ($output -notmatch 'Successfully paired') {
        Write-Fail "Eşleştirme başarısız: $output"
        return $null
    }
    Write-Ok 'Eşleştirildi.'
    $connectAddress = Read-Text "Kablosuz hata ayıklama ana ekranındaki IP:port (eşleştirme portu değil)"
    return Connect-Tv $connectAddress
}

<#
  Picks the TV to work on and stores it in $script:Serial.
  An IP address connects over the network; anything else is treated as an adb serial (e.g. emulator-5554).
#>
function Select-Device([string]$Requested) {
    $serial = $null
    if ($Requested) {
        if ($Requested -match '^\d{1,3}(\.\d{1,3}){3}(:\d+)?$') {
            $serial = Connect-Tv $Requested
        } else {
            $serial = $Requested
        }
    } else {
        $ready = @(Get-AdbDevices | Where-Object State -eq 'device')
        if ($ready.Count -eq 1) {
            $serial = $ready[0].Serial
        } elseif ($ready.Count -gt 1) {
            if ($script:AssumeYes) { throw "Birden fazla cihaz bağlı; -Device ile hangisi olduğunu belirt: $($ready.Serial -join ', ')" }
            Write-Step 'Birden fazla cihaz bağlı:'
            for ($i = 0; $i -lt $ready.Count; $i++) { Write-Info "[$($i + 1)] $($ready[$i].Serial)" }
            $pick = [int](Read-Text 'Hangisi?' '1')
            $serial = $ready[$pick - 1].Serial
        } else {
            $last = Get-LastDevice
            if ($last) { $serial = Connect-Tv $last }
            if (-not $serial -and -not $script:AssumeYes) {
                $address = Read-Text "TV'nin IP adresi (TV: Ayarlar > Ağ ve İnternet'te yazar)"
                if ($address) { $serial = Connect-Tv $address }
            }
        }
    }

    if (-not $serial) { throw 'Bağlı bir TV yok.' }
    $state = (Get-AdbDevices | Where-Object Serial -eq $serial).State
    if ($state -ne 'device') { throw "$serial hazır değil (durum: $state)." }
    $script:Serial = $serial
    return $serial
}

function Get-DeviceName {
    $model = Get-Prop 'ro.product.model'
    return "$model ($script:Serial)"
}

function Get-SdkLevel { return [int](Get-Prop 'ro.build.version.sdk') }

function Get-MemoryMB {
    $info = Invoke-Shell 'cat /proc/meminfo'
    $total = [regex]::Match($info, 'MemTotal:\s+(\d+)').Groups[1].Value
    $available = [regex]::Match($info, 'MemAvailable:\s+(\d+)').Groups[1].Value
    return [pscustomobject]@{ Total = [int]([long]$total / 1024); Available = [int]([long]$available / 1024) }
}

function Get-PackageSet([string]$Flags = '') {
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($line in ((Invoke-Shell "pm list packages $Flags") -split "`n")) {
        if ($line -match '^package:(.+)$') { [void]$set.Add($Matches[1].Trim()) }
    }
    return , $set
}

function Test-Resumed([string]$Package) {
    $activities = Invoke-Shell "dumpsys activity activities | grep -E 'ResumedActivity'" -AllowFail
    return $activities -match [regex]::Escape("$Package/")
}

function Wait-Resumed([string]$Package, [int]$Seconds = 10) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Resumed $Package) { return $true }
    }
    return $false
}
