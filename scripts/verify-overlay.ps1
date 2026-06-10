param([string]$Device = 'emulator-5554')
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$adb = 'C:\Users\PIXEL\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$base = 'G:\development\entertainment\moviebox-tv'

function Dump-Ui {
    & $adb -s $Device shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $adb -s $Device pull /sdcard/ui.xml "$base\ui.xml" 2>$null | Out-Null
    return (Get-Content "$base\ui.xml" -Raw)
}
function Find-Bounds($ui, $needle) {
    $m = [regex]::Match($ui, "$needle[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $m.Success) { return $null }
    @{
        cx = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
        cy = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    }
}
function Shot($name) {
    & $adb -s $Device shell screencap -p /sdcard/s.png | Out-Null
    & $adb -s $Device pull /sdcard/s.png "$base\$name-full.png" 2>$null | Out-Null
    # Downscale (the cap is too big for the image API)
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile("$base\$name-full.png")
    # Cap BOTH dimensions at 1800 (image API limit is 2000).
    $maxDim = 1800.0
    $scale = [Math]::Min($maxDim/$img.Width, $maxDim/$img.Height)
    if ($scale -gt 1) { $scale = 1 }
    $nw = [int]($img.Width * $scale); $nh = [int]($img.Height * $scale)
    $bmp = New-Object System.Drawing.Bitmap($nw, $nh)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($img, 0, 0, $nw, $nh); $g.Dispose()
    $bmp.Save("$base\$name.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose(); $img.Dispose()
    Remove-Item "$base\$name-full.png"
    "saved $name.png (${nw}x${nh})"
}

& $adb -s $Device shell settings put system accelerometer_rotation 0 | Out-Null
& $adb -s $Device shell settings put system user_rotation 0 | Out-Null
& $adb -s $Device shell am force-stop com.moviebox.tv
& $adb -s $Device shell am start -n com.moviebox.tv/.MainActivity | Out-Null
Start-Sleep -Seconds 6

# 1) Open the transparent QR overlay
$ui = Dump-Ui
$bn = Find-Bounds $ui 'content-desc="Mobile Remote"'
if ($bn) {
    "tap remote @ $($bn.cx),$($bn.cy)"
    & $adb -s $Device shell input tap $bn.cx $bn.cy
} else { 'Mobile Remote icon not found'; exit 1 }
Start-Sleep -Seconds 3
$ui = Dump-Ui
$overlayShown = [bool]([regex]::Match($ui, 'Phone Remote').Success)
"overlay rendered: $overlayShown"
Shot 'overlay'

# 2) Close overlay (back), wait, then open Settings via icon
& $adb -s $Device shell input keyevent 4
Start-Sleep -Seconds 2
$ui = Dump-Ui
$bn = Find-Bounds $ui 'content-desc="Settings"'
if ($bn) {
    "tap settings @ $($bn.cx),$($bn.cy)"
    & $adb -s $Device shell input tap $bn.cx $bn.cy
    Start-Sleep -Seconds 3
} else { 'Settings icon not found' }
$ui = Dump-Ui
$settingsShown = [bool]([regex]::Match($ui, 'Allow all devices').Success)
"settings rendered: $settingsShown"
Shot 'settings'

# 3) Restore rotation
& $adb -s $Device shell settings put system accelerometer_rotation 1 | Out-Null
"done"
