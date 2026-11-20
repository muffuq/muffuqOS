<#
  Per-TV record of everything muffuqOS changed, so "Geri al" can put it back exactly.
  Stored in backups/<device-id>.json. Keyed by the TV's hardware serial rather than its IP, because
  the IP changes whenever the router hands out a new one.
#>

function Get-DeviceId {
    $id = Get-Prop 'ro.serialno'
    if (-not $id -or $id -eq 'unknown') { $id = Get-Prop 'ro.boot.serialno' }
    if (-not $id -or $id -eq 'unknown') { $id = $script:Serial }
    $model = Get-Prop 'ro.product.device'
    return ("$model-$id" -replace '[^A-Za-z0-9._-]', '_')
}

function Get-StatePath {
    $dir = Join-Path $script:ProjectRoot 'backups'
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    return Join-Path $dir "$(Get-DeviceId).json"
}

function New-DeviceState {
    return [pscustomobject]@{
        device           = Get-Prop 'ro.product.model'
        created          = (Get-Date).ToString('s')
        disabled         = @()
        launcherDisabled = @()
        settings         = @()
    }
}

function Get-DeviceState {
    $path = Get-StatePath
    if (-not (Test-Path $path)) { return New-DeviceState }
    $state = Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json
    # JSON round-trips can turn single-item arrays into scalars; normalise.
    $state.disabled = @($state.disabled | Where-Object { $_ })
    $state.launcherDisabled = @($state.launcherDisabled | Where-Object { $_ })
    $state.settings = @($state.settings | Where-Object { $_ })
    return $state
}

function Save-DeviceState($State) {
    $State | ConvertTo-Json -Depth 5 | Set-Content -Path (Get-StatePath) -Encoding UTF8
}

function Test-StateEmpty($State) {
    return ($State.disabled.Count + $State.launcherDisabled.Count + $State.settings.Count) -eq 0
}

<#
  Disables a package for user 0 and remembers it under $List ('disabled' or 'launcherDisabled').
  Returns $true when the package ended up disabled.
#>
function Disable-TrackedPackage($State, [string]$Package, [string]$List = 'disabled') {
    $output = Invoke-Shell "pm disable-user --user 0 $Package" -AllowFail
    if ($output -notmatch 'disabled') { return $false }
    if ($State.$List -notcontains $Package) { $State.$List = @($State.$List) + $Package }
    Save-DeviceState $State
    return $true
}

<#
  Changes a `settings` value, remembering the value from before muffuqOS ever touched it.
  Running the tool twice must not overwrite the true original with our own value.
#>
function Set-TrackedSetting($State, [string]$Namespace, [string]$Key, [string]$Value) {
    $known = $State.settings | Where-Object { $_.namespace -eq $Namespace -and $_.key -eq $Key }
    if (-not $known) {
        $original = (Invoke-Shell "settings get $Namespace $Key").Trim()
        $State.settings = @($State.settings) + [pscustomobject]@{ namespace = $Namespace; key = $Key; value = $original }
        Save-DeviceState $State
    }
    Invoke-Shell "settings put $Namespace $Key $Value" | Out-Null
}
