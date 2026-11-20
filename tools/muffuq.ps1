#Requires -Version 5.1
<#
.SYNOPSIS
    muffuqOS TV araç kutusu: Android TV'yi hızlandırır ve muffuqOS launcher'ını kurar.

.DESCRIPTION
    Parametresiz çalıştırınca menü açılır. Komutlar doğrudan da çalıştırılabilir:

      .\muffuq.ps1 -Command status   -Device 192.168.1.50
      .\muffuq.ps1 -Command speed    -Level recommended -Yes
      .\muffuq.ps1 -Command launcher
      .\muffuq.ps1 -Command restore  -UninstallLauncher

    Yapılan her değişiklik backups\ klasörüne kaydedilir; "restore" hepsini geri alır.
#>
[CmdletBinding()]
param(
    [ValidateSet('menu', 'connect', 'status', 'speed', 'launcher', 'restore')]
    [string]$Command = 'menu',

    # TV'nin IP adresi (192.168.1.50) ya da adb seri numarası (emulator-5554)
    [string]$Device,

    [ValidateSet('safe', 'recommended', 'aggressive')]
    [string]$Level = 'recommended',

    [string]$Animation = '0.5',

    # Hazır bir launcher APK'sı; verilmezse dist\muffuqOS-launcher.apk
    [string]$Apk,

    [switch]$KeepStockLauncher,
    [switch]$UninstallLauncher,

    # Soru sormadan varsayılanlarla ilerle
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$OutputEncoding = [System.Text.Encoding]::UTF8

$script:ToolsDir = $PSScriptRoot
$script:ProjectRoot = Split-Path $PSScriptRoot -Parent
$script:AssumeYes = [bool]$Yes

foreach ($module in (Get-ChildItem (Join-Path $PSScriptRoot 'lib') -Filter '*.ps1')) {
    . $module.FullName
}

function Start-Menu {
    while ($true) {
        Write-Banner
        if ($script:Serial) {
            Write-Info "Bağlı TV: $(Get-DeviceName)"
        } else {
            Write-Info 'Bağlı TV yok'
        }
        Write-Host ''
        Write-Host '  [1] TV''ye bağlan'
        Write-Host '  [2] Eşleştirme koduyla bağlan (Android 11+ kablosuz hata ayıklama)'
        Write-Host '  [3] Durum raporu'
        Write-Host '  [4] Hızlandır (gereksiz servisleri kapat, animasyon, önbellek)'
        Write-Host '  [5] muffuqOS launcher''ı kur ve ana ekran yap'
        Write-Host '  [6] Her şeyi geri al'
        Write-Host '  [0] Çıkış'
        $choice = Read-Text 'Seçim'

        try {
            switch ($choice) {
                '1' {
                    $address = Read-Text "TV'nin IP adresi" (Get-LastDevice)
                    if ($address) { $script:Serial = $null; Select-Device $address | Out-Null; Show-Status }
                }
                '2' {
                    $serial = Connect-WithPairing
                    if ($serial) { $script:Serial = $serial; Show-Status }
                }
                '3' { Confirm-Device; Show-Status }
                '4' {
                    Confirm-Device
                    Write-Info 'Hazır ayar: [1] güvenli  [2] önerilen  [3] agresif'
                    $preset = switch (Read-Text 'Seçim' '2') { '1' { 'safe' } '3' { 'aggressive' } default { 'recommended' } }
                    Invoke-Speed -Level $preset -Animation $Animation
                }
                '5' {
                    Confirm-Device
                    $keep = -not (Read-YesNo 'Stok launcher kapatılsın mı? (Home tuşunun muffuqOS''a gitmesi için gerekli)')
                    Install-Launcher -Apk $Apk -KeepStock:$keep
                }
                '6' { Confirm-Device; Invoke-Restore }
                '0' { return }
            }
        } catch {
            Write-Fail $_.Exception.Message
        }
    }
}

function Confirm-Device {
    if (-not $script:Serial) { Select-Device $Device | Out-Null }
}

Initialize-Adb
if ($Command -eq 'menu') {
    try { Select-Device $Device | Out-Null } catch { Write-Warn $_.Exception.Message }
    Start-Menu
    return
}

Select-Device $Device | Out-Null
Write-Banner
Write-Info "Bağlı TV: $(Get-DeviceName)"
switch ($Command) {
    'connect' { Show-Status }
    'status' { Show-Status }
    'speed' { Invoke-Speed -Level $Level -Animation $Animation }
    'launcher' { Install-Launcher -Apk $Apk -KeepStock:$KeepStockLauncher }
    'restore' { Invoke-Restore -UninstallLauncher:$UninstallLauncher }
}
