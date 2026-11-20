# Console helpers. ASCII status markers on purpose: the default Windows console font has no check-mark glyphs.

function Write-Banner {
    Write-Host ''
    Write-Host '  muffuq' -NoNewline -ForegroundColor White
    Write-Host 'OS' -NoNewline -ForegroundColor Magenta
    Write-Host '  ·  TV araç kutusu' -ForegroundColor DarkGray
}

function Write-Title([string]$Text) {
    Write-Host ''
    Write-Host "  $Text" -ForegroundColor Magenta
    Write-Host ('  ' + ('-' * $Text.Length)) -ForegroundColor DarkGray
}

function Write-Step([string]$Text) { Write-Host "  > $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "  [+] $Text" -ForegroundColor Green }
function Write-Warn([string]$Text) { Write-Host "  [!] $Text" -ForegroundColor Yellow }
function Write-Fail([string]$Text) { Write-Host "  [x] $Text" -ForegroundColor Red }
function Write-Info([string]$Text) { Write-Host "      $Text" -ForegroundColor Gray }

function Read-YesNo([string]$Question, [bool]$Default = $true) {
    if ($script:AssumeYes) { return $Default }
    $hint = if ($Default) { '[E/h]' } else { '[e/H]' }
    while ($true) {
        $answer = (Read-Host "  ? $Question $hint").Trim().ToLowerInvariant()
        if (-not $answer) { return $Default }
        if ($answer -in @('e', 'evet', 'y', 'yes')) { return $true }
        if ($answer -in @('h', 'hayır', 'hayir', 'n', 'no')) { return $false }
    }
}

function Read-Text([string]$Prompt, [string]$Default = '') {
    $suffix = if ($Default) { " [$Default]" } else { '' }
    $answer = (Read-Host "  ? $Prompt$suffix").Trim()
    if ($answer) { return $answer }
    return $Default
}

function Show-DevModeHelp {
    Write-Host ''
    Write-Info "TV'de geliştirici modunu açmak için:"
    Write-Info '  1. Ayarlar > Cihaz Tercihleri > Hakkında'
    Write-Info "  2. 'Derleme' (Build) satırında OK'ye 7 kez bas"
    Write-Info "  3. Geri gel > Geliştirici seçenekleri > 'USB hata ayıklama'yı aç"
    Write-Info "     (varsa 'Ağ üzerinden hata ayıklama'yı da aç)"
    Write-Info "  4. TV'de çıkan izin penceresinde 'Her zaman izin ver'i işaretleyip onayla"
    Write-Host ''
}
