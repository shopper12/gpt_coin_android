param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$ReportPath = "reports/latest.json",
    [string]$RulesPath = "rules/strategy-rules.json",
    [string]$RationalePath = "reports/rule-change-rationale.md",
    [int]$PollSeconds = 30,
    [switch]$Once,
    [switch]$AutoCommit
)

$ErrorActionPreference = "Stop"

function Resolve-InRepoPath([string]$RelativePath) {
    $full = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $RelativePath))
    $root = [System.IO.Path]::GetFullPath($RepoRoot)
    if (-not $full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes repo root: $RelativePath"
    }
    return $full
}

function Read-JsonFile([string]$Path) {
    Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 100
}

function Get-NumericLeaves($Value, [string]$Prefix = "") {
    $items = @{}
    if ($null -eq $Value) {
        return $items
    }
    if ($Value -is [int] -or $Value -is [long] -or $Value -is [double] -or $Value -is [decimal]) {
        $items[$Prefix] = [double]$Value
        return $items
    }
    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string]) -and -not ($Value -is [pscustomobject])) {
        $index = 0
        foreach ($entry in $Value) {
            foreach ($pair in (Get-NumericLeaves $entry "$Prefix[$index]").GetEnumerator()) {
                $items[$pair.Key] = $pair.Value
            }
            $index++
        }
        return $items
    }
    if ($Value -is [pscustomobject]) {
        foreach ($property in $Value.PSObject.Properties) {
            $name = if ($Prefix) { "$Prefix.$($property.Name)" } else { $property.Name }
            foreach ($pair in (Get-NumericLeaves $property.Value $name).GetEnumerator()) {
                $items[$pair.Key] = $pair.Value
            }
        }
    }
    return $items
}

function Assert-RuleChangeLimit([string]$BeforePath, [string]$AfterPath) {
    $before = Get-NumericLeaves (Read-JsonFile $BeforePath)
    $after = Get-NumericLeaves (Read-JsonFile $AfterPath)
    foreach ($key in $after.Keys) {
        if (-not $before.ContainsKey($key)) {
            continue
        }
        $old = [double]$before[$key]
        $new = [double]$after[$key]
        if ([Math]::Abs($old) -lt 0.0000001) {
            if ([Math]::Abs($new - $old) -gt 0.1) {
                throw "Rule change exceeds zero-base guard at $key"
            }
            continue
        }
        $changeRatio = [Math]::Abs(($new - $old) / $old)
        if ($changeRatio -gt 0.10) {
            throw "Rule change exceeds 10 percent at $key ($old -> $new)"
        }
    }
}

function Get-FileHashText([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return ""
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Invoke-StrategyAnalysis {
    $reportFull = Resolve-InRepoPath $ReportPath
    $rulesFull = Resolve-InRepoPath $RulesPath
    $rationaleFull = Resolve-InRepoPath $RationalePath
    if (-not (Test-Path -LiteralPath $reportFull)) {
        Write-Host "[strategy-agent] report not found: $ReportPath"
        return
    }
    if (-not (Test-Path -LiteralPath $rulesFull)) {
        throw "Rules file not found: $RulesPath"
    }

    $tempRules = Join-Path ([System.IO.Path]::GetTempPath()) ("strategy-rules-before-" + [Guid]::NewGuid().ToString("N") + ".json")
    Copy-Item -LiteralPath $rulesFull -Destination $tempRules

    $prompt = @"
You are a local strategy-analysis agent for a personal Android coin scanner.

Read:
- $ReportPath
- $RulesPath

Allowed writes:
- $RulesPath
- $RationalePath

Do not modify Kotlin, Gradle, Manifest, tools, or any other files.
Do not run git reset, git clean, deletion commands, or dependency refresh commands.
Limit every numeric rule change to 10 percent or less from the previous value.
If the report does not justify a rule change, leave $RulesPath unchanged and explain why in $RationalePath.
Write valid JSON only to $RulesPath.
Write a concise rationale with evidence from $ReportPath to $RationalePath.
"@

    Push-Location $RepoRoot
    try {
        codex exec --ask-for-approval never --sandbox danger-full-access $prompt

        $allowed = @(
            ([System.IO.Path]::GetFullPath($rulesFull)),
            ([System.IO.Path]::GetFullPath($rationaleFull))
        )
        $changed = git diff --name-only
        foreach ($relative in $changed) {
            $full = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $relative))
            if ($allowed -notcontains $full) {
                throw "Unexpected modified file: $relative"
            }
        }

        Read-JsonFile $rulesFull | Out-Null
        Assert-RuleChangeLimit -BeforePath $tempRules -AfterPath $rulesFull

        if (-not (Test-Path -LiteralPath $rationaleFull)) {
            throw "Missing rationale file: $RationalePath"
        }

        if ($AutoCommit) {
            git add -- $RulesPath $RationalePath
            git commit -m "Update strategy rules from latest report"
            git push
        } else {
            git diff -- $RulesPath $RationalePath
        }
    } finally {
        Pop-Location
    }
}

$reportFullPath = Resolve-InRepoPath $ReportPath
$lastHash = Get-FileHashText $reportFullPath

do {
    $currentHash = Get-FileHashText $reportFullPath
    if ($currentHash -and $currentHash -ne $lastHash) {
        Write-Host "[strategy-agent] report changed; running strategy analysis"
        Invoke-StrategyAnalysis
        $lastHash = Get-FileHashText $reportFullPath
    } elseif ($Once -and $currentHash) {
        Invoke-StrategyAnalysis
    }

    if (-not $Once) {
        Start-Sleep -Seconds $PollSeconds
    }
} while (-not $Once)

