# PowerShell script to migrate hardcoded .sp values to FontSize.XX
# Uses UTF-8 encoding to avoid corruption

$ErrorActionPreference = "Stop"

# FontSize tier mapping
$map = @{
    11="Caption";12="Caption";13="Caption"
    14="Small";15="Small";16="Small"
    17="Body";18="Body"
    19="Button";20="Button";21="Button"
    22="Subtitle";23="Subtitle";24="Subtitle";25="Subtitle";26="Subtitle"
    27="Title";28="Title";29="Title";30="Title"
    31="Display";32="Display";33="Display";34="Display";35="Display";36="Display";37="Display";38="Display";39="Display"
    40="DisplayLarge";41="DisplayLarge";42="DisplayLarge";43="DisplayLarge";44="DisplayLarge";45="DisplayLarge";46="DisplayLarge";47="DisplayLarge"
}

$importLine = "import com.nasmusic.tv.ui.theme.FontSize"

# Find ALL .kt files under ui/ that contain fontSize = XX.sp
$files = Get-ChildItem -Path "app\src\main\java\com\nasmusic\tv\ui" -Recurse -Filter "*.kt" |
    Where-Object { (Get-Content $_.FullName -Raw -Encoding UTF8) -match "fontSize\s*=\s*\d+\.sp" }

Write-Host "Found $($files.Count) files with .sp values"

foreach ($file in $files) {
    Write-Host "`nProcessing: $($file.Name)"
    
    # Read with explicit UTF-8 encoding
    $content = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    $originalContent = $content
    
    # Add FontSize import if missing
    if ($content -notmatch "import com\.nasmusic\.tv\.ui\.theme\.FontSize") {
        $content = $content -replace "(package\s+com\.nasmusic\.tv\.ui\.[^\r\n]+)", "`$1`r`n`r`n$importLine"
    }
    
    # Replace fontSize = XX.sp (but NOT fontSize = XX.sp *)
    # Use regex with negative lookahead for the multiplication case
    $pattern = 'fontSize\s*=\s*(\d+)\.sp(?!\s*\*)'
    $matches = [regex]::Matches($content, $pattern)
    
    $count = 0
    foreach ($m in $matches) {
        $spVal = [int]$m.Groups[1].Value
        if ($map.ContainsKey($spVal)) {
            $tier = $map[$spVal]
            $old = $m.Value
            $new = "fontSize = FontSize.$tier"
            $content = $content.Replace($old, $new)
            $count++
        } else {
            Write-Host "  WARNING: No mapping for $spVal.sp"
        }
    }
    
    if ($count -gt 0) {
        # Write back with UTF-8 no BOM
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($file.FullName, $content, $utf8NoBom)
        Write-Host "  Replaced $count values"
    } else {
        Write-Host "  No replacements made"
    }
}

Write-Host "`nDone!"