# PowerShell script to migrate hardcoded .sp values to FontSize.XX
# This script replaces `fontSize = XX.sp` with `FontSize.XX`

$ErrorActionPreference = "Stop"

# FontSize tier mapping: XX.sp -> FontSize.XX
$fontSizeMap = @{
    "11" = "Caption"
    "12" = "Caption"
    "13" = "Caption"
    "14" = "Small"
    "15" = "Small"
    "16" = "Small"
    "17" = "Body"
    "18" = "Body"
    "19" = "Button"
    "20" = "Button"
    "21" = "Button"
    "22" = "Subtitle"
    "23" = "Subtitle"
    "24" = "Subtitle"
    "25" = "Subtitle"
    "26" = "Subtitle"
    "27" = "Title"
    "28" = "Title"
    "29" = "Title"
    "30" = "Title"
    "31" = "Display"
    "32" = "Display"
    "33" = "Display"
    "34" = "Display"
    "35" = "Display"
    "36" = "Display"
    "37" = "Display"
    "38" = "Display"
    "39" = "Display"
    "40" = "DisplayLarge"
    "41" = "DisplayLarge"
    "42" = "DisplayLarge"
    "43" = "DisplayLarge"
    "44" = "DisplayLarge"
    "45" = "DisplayLarge"
    "46" = "DisplayLarge"
    "47" = "DisplayLarge"
}

# Files to process (all modified files with hardcoded .sp values)
$files = @(
    "app\src\main\java\com\nasmusic\tv\ui\screens\SettingsScreen.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\ServerConnectScreen.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\netdisk\BaiduAuthDialog.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\netdisk\TextInputDialog.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\netdisk\NetdiskScreen.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\EqualizerScreen.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\library\DiscoverTab.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\AlbumDetailScreen.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\LyricsSettingsDialog.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\common\ActionBar.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\common\ListStateIndicators.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\ExitConfirmDialog.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\ConnectPromptDialog.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\library\JamendoTab.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\LyricsView.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\AppRoot.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\common\SectionHeader.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\MvPlaybackScreen.kt",
    "app\src\main\java\com\nasmusic\tv\ui\MainActivity.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\song\UnifiedSongGrid.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\library\SearchTab.kt",
    "app\src\main\java\com\nasmusic\tv\ui\screens\ArtistDetailScreen.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\KaraokeLyricsView.kt",
    "app\src\main\java\com\nasmusic\tv\ui\components\BaiduDirPickerDialog.kt"
)

$importLine = "import com.nasmusic.tv.ui.theme.FontSize"

foreach ($file in $files) {
    if (Test-Path $file) {
        Write-Host "Processing: $file"
        $content = Get-Content -Path $file -Raw
        
        # Check if FontSize import already exists
        if ($content -notmatch "import com\.nasmusic\.tv\.ui\.theme\.FontSize") {
            # Add FontSize import after package line
            $content = $content -replace "(package\s+com\.nasmusic\.tv\.ui\.[^\r\n]+)", "`$1`r`n`r`n$importLine"
        }
        
        # Replace fontSize = XX.sp with FontSize.XX (but not if followed by *)
        # This regex matches: fontSize = XX.sp (NOT followed by *)
        $pattern = "fontSize\s*=\s*(\d+)\.sp(?!\s*\*)"
        $matches = [regex]::Matches($content, $pattern)
        
        if ($matches.Count -gt 0) {
            Write-Host "  Found $($matches.Count) .sp values to replace"
            
            foreach ($match in $matches) {
                $spValue = $match.Groups[1].Value
                if ($fontSizeMap.ContainsKey($spValue)) {
                    $fontSizeTier = $fontSizeMap[$spValue]
                    $oldValue = $match.Value
                    $newValue = "fontSize = FontSize.$fontSizeTier"
                    $content = $content.Replace($oldValue, $newValue)
                    Write-Host "    $oldValue -> $newValue"
                } else {
                    Write-Host "    WARNING: No mapping for $spValue.sp"
                }
            }
            
            # Write the modified content back
            Set-Content -Path $file -Value $content -NoNewline
            Write-Host "  Updated: $file"
        } else {
            Write-Host "  No .sp values found in $file"
        }
    } else {
        Write-Host "WARNING: File not found: $file"
    }
}

Write-Host "`nMigration complete!"