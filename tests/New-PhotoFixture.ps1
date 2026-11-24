param([Parameter(Mandatory)][string]$Root)
$ErrorActionPreference = 'Stop'
if (Test-Path $Root) { throw "Choose a new fixture directory" }
Add-Type -AssemblyName System.Drawing
$src = New-Item -ItemType Directory -Path (Join-Path $Root 'Sender/Album')
$hub = New-Item -ItemType Directory -Path (Join-Path $Root 'Hub/Manual')
for ($i=1; $i -le 100; $i++) {
    $bitmap = New-Object Drawing.Bitmap(24,24)
    try {
        $bitmap.SetPixel(12,12,[Drawing.Color]::FromArgb(255,$i,100,255-$i))
        $path = Join-Path $src.FullName ("Photo-{0:D3}.png" -f $i)
        $bitmap.Save($path,[Drawing.Imaging.ImageFormat]::Png)
    } finally { $bitmap.Dispose() }
    if ($i -le 80) { Copy-Item -LiteralPath $path -Destination (Join-Path $hub.FullName ("Renamed-{0}.png" -f $i)) }
}

