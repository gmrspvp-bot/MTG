# Builds the "Jarvis" Forge skin: an arc-reactor palette (deep navy, cyan, amber) in place of
# Forge's near-black default. Second owner's request, 2026-10-20.
#
# Forge reads its entire UI palette from SINGLE PIXELS in the skin's bg_splash.png, at x=70 and
# the y values below (forge.localinstance.skin.FSkinProp). Recolouring those sixteen pixels
# restyles every panel, border, tab, phase indicator and targeting arrow in the desktop UI, with
# no code change at all. The two big background textures are additionally desaturated and tinted
# blue so the table matches.
#
# The skin is generated into the Forge install (which is not in git), so this script is the thing
# that is version-controlled. It is safe to re-run: it rebuilds the folder from scratch.
#
#   powershell -ExecutionPolicy Bypass -File scripts\make-jarvis-skin.ps1
#
# Then set UI_SKIN=Jarvis (scripts\play.ps1 does this once, on first launch after pulling).

param(
    [string] $Name = 'Jarvis',
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root    = Split-Path -Parent $PSScriptRoot
$skins   = Join-Path $root 'forge-installer-2.0.15\res\skins'
$source  = Join-Path $skins 'default'
$target  = Join-Path $skins $Name

if (-not (Test-Path $source)) { throw "Forge's default skin not found at $source - is Forge extracted into forge-installer-2.0.15\?" }
if ((Test-Path $target) -and -not $Force) { Remove-Item -Recurse -Force $target }

Write-Host "Copying default skin -> $Name"
Copy-Item -Recurse -Force $source $target

# --- the palette -------------------------------------------------------------------------------
# y position in bg_splash.png -> the colour Forge reads from it.
$palette = [ordered]@{
    '10'  = '#05131C'   # CLR_THEME                     main panel background
    '30'  = '#1C5D73'   # CLR_BORDERS                   panel edges and dividers
    '50'  = '#0A2231'   # CLR_ZEBRA                     alternating list rows
    '70'  = '#10405A'   # CLR_HOVER                     mouse-over highlight
    '90'  = '#6FE3FF'   # CLR_ACTIVE                    selected tab / active text
    '110' = '#061826'   # CLR_INACTIVE                  unselected tab
    '130' = '#9FE8FF'   # CLR_TEXT                      body text
    '150' = '#06202E'   # CLR_PHASE_INACTIVE_ENABLED
    '170' = '#09161D'   # CLR_PHASE_INACTIVE_DISABLED
    '190' = '#17809E'   # CLR_PHASE_ACTIVE_ENABLED      the lit phase box
    '210' = '#0B3242'   # CLR_PHASE_ACTIVE_DISABLED
    '230' = '#082A3A'   # CLR_THEME2                    secondary panels
    '250' = '#0A2634'   # CLR_OVERLAY                   dimming overlay
    '270' = '#FFA62B'   # CLR_COMBAT_TARGETING_ARROW    amber: attacks
    '290' = '#38C6F4'   # CLR_NORMAL_TARGETING_ARROW    cyan: targeting
    '310' = '#C86BFF'   # CLR_PWATTK_TARGETING_ARROW    violet: planeswalker attacks
}

function ConvertTo-Color([string] $hex) {
    $h = $hex.TrimStart('#')
    return [System.Drawing.Color]::FromArgb(255,
        [Convert]::ToInt32($h.Substring(0, 2), 16),
        [Convert]::ToInt32($h.Substring(2, 2), 16),
        [Convert]::ToInt32($h.Substring(4, 2), 16))
}

$splashes = @('bg_splash.png', 'bg_splash_hd.png')
foreach ($file in $splashes) {
    $path = Join-Path $target $file
    if (-not (Test-Path $path)) { continue }
    # Load via a stream so the file is not left locked while we write it back.
    $bytes = [IO.File]::ReadAllBytes($path)
    $ms    = New-Object IO.MemoryStream(,$bytes)
    $img   = [System.Drawing.Image]::FromStream($ms)
    $bmp   = New-Object System.Drawing.Bitmap($img)
    $img.Dispose(); $ms.Dispose()

    # The HD splash is double size, so its palette pixels sit at twice the y.
    $scale = [math]::Round($bmp.Height / 550.0)
    if ($scale -lt 1) { $scale = 1 }
    foreach ($y in $palette.Keys) {
        $c  = ConvertTo-Color $palette[$y]
        $px = 70 * $scale
        $py = [int]$y * $scale
        # Paint a small block, not one pixel: any resampling Forge does still lands on the colour.
        for ($dx = -3; $dx -le 3; $dx++) {
            for ($dy = -3; $dy -le 3; $dy++) {
                $x2 = $px + $dx; $y2 = $py + $dy
                if ($x2 -ge 0 -and $y2 -ge 0 -and $x2 -lt $bmp.Width -and $y2 -lt $bmp.Height) {
                    $bmp.SetPixel($x2, $y2, $c)
                }
            }
        }
    }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "  palette written into $file (scale x$scale)"
}

# --- background textures -----------------------------------------------------------------------
# Desaturate to luminance, then tint towards cyan and darken, so the table reads as a HUD.
function Set-Tint([string] $path, [double] $tr, [double] $tg, [double] $tb, [double] $gain) {
    if (-not (Test-Path $path)) { return }
    $bytes = [IO.File]::ReadAllBytes($path)
    $ms    = New-Object IO.MemoryStream(,$bytes)
    $src   = [System.Drawing.Image]::FromStream($ms)
    $w = $src.Width; $h = $src.Height
    $out = New-Object System.Drawing.Bitmap($w, $h)
    $g   = [System.Drawing.Graphics]::FromImage($out)

    $m = New-Object System.Drawing.Imaging.ColorMatrix
    $m.Matrix00 = 0.30 * $tr * $gain; $m.Matrix01 = 0.30 * $tg * $gain; $m.Matrix02 = 0.30 * $tb * $gain
    $m.Matrix10 = 0.59 * $tr * $gain; $m.Matrix11 = 0.59 * $tg * $gain; $m.Matrix12 = 0.59 * $tb * $gain
    $m.Matrix20 = 0.11 * $tr * $gain; $m.Matrix21 = 0.11 * $tg * $gain; $m.Matrix22 = 0.11 * $tb * $gain
    $m.Matrix33 = 1.0; $m.Matrix44 = 1.0

    $ia = New-Object System.Drawing.Imaging.ImageAttributes
    $ia.SetColorMatrix($m)
    $rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
    $g.DrawImage($src, $rect, 0, 0, $w, $h, [System.Drawing.GraphicsUnit]::Pixel, $ia)
    $g.Dispose(); $src.Dispose(); $ms.Dispose()

    $ext = [IO.Path]::GetExtension($path).ToLower()
    if ($ext -eq '.png') { $fmt = [System.Drawing.Imaging.ImageFormat]::Png }
    else                 { $fmt = [System.Drawing.Imaging.ImageFormat]::Jpeg }
    $out.Save($path, $fmt)
    $out.Dispose()
    Write-Host "  tinted $(Split-Path -Leaf $path)"
}

foreach ($bg in @('bg_texture.jpg', 'bg_match.jpg', 'bg_day.jpg', 'bg_night.jpg')) {
    Set-Tint (Join-Path $target $bg) 0.35 0.75 1.00 0.80
}

Write-Host ""
Write-Host "Built skin '$Name' at $target"
Write-Host "Set UI_SKIN=$Name in forge-data\preferences\forge.preferences (play.ps1 does this once)."
