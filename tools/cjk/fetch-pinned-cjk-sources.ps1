<#
  SPDX-License-Identifier: GPL-3.0-only

  Fetches the exact Rime/Mozc source revisions approved for FrostKeys. This script intentionally
  does not build native libraries, download runtime data, or modify the app workspace. It refuses
  dirty existing checkouts so a source acquisition cannot discard local work.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Destination,

    [Alias('Engine')]
    [string[]]$EngineId = @()
)

$ErrorActionPreference = 'Stop'
$sourceLock = Join-Path $PSScriptRoot 'engine-sources.json'
if (-not (Test-Path -LiteralPath $sourceLock -PathType Leaf)) {
    throw "CJK source lock is missing: $sourceLock"
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw 'git is required to acquire pinned CJK sources.'
}

$lock = Get-Content -LiteralPath $sourceLock -Raw | ConvertFrom-Json
if ($lock.schema -ne 1 -or $null -eq $lock.engines) {
    throw 'Unsupported or malformed CJK source lock.'
}

$engines = @($lock.engines)
if ($EngineId.Count -gt 0) {
    $requested = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($id in $EngineId) {
        if (-not $requested.Add($id)) {
            throw "Engine was requested more than once: $id"
        }
    }
    $engines = @($engines | Where-Object { $requested.Contains($_.id) })
    if ($engines.Count -ne $requested.Count) {
        throw 'One or more requested engine ids are absent from the CJK source lock.'
    }
}

$destinationRoot = [System.IO.Path]::GetFullPath($Destination)
if (-not (Test-Path -LiteralPath $destinationRoot)) {
    New-Item -ItemType Directory -Path $destinationRoot | Out-Null
}

foreach ($engine in $engines) {
    if ($engine.id -notmatch '^[A-Za-z0-9._-]{1,80}$') {
        throw "Unsafe engine id in source lock: $($engine.id)"
    }
    if ($engine.commit -notmatch '^[0-9a-f]{40}$' -or $engine.checkoutCommit -notmatch '^[0-9a-f]{40}$') {
        throw "Source lock does not contain full immutable revisions for $($engine.id)"
    }
    if ($engine.checkoutSource -notmatch '^https://github\.com/[^/]+/[^/]+\.git$') {
        throw "Source lock has an unsupported checkout URL for $($engine.id)"
    }
    if ($engine.fetchRef -notmatch '^(refs/[A-Za-z0-9._/-]+|[0-9a-f]{40})$') {
        throw "Source lock has an unsafe fetch ref for $($engine.id)"
    }

    $checkoutPath = Join-Path $destinationRoot $engine.id
    $gitDirectory = Join-Path $checkoutPath '.git'
    if (Test-Path -LiteralPath $checkoutPath) {
        if (-not (Test-Path -LiteralPath $gitDirectory -PathType Container)) {
            throw "Refusing to use non-git checkout path: $checkoutPath"
        }
        $dirty = git -C $checkoutPath status --porcelain
        if ($LASTEXITCODE -ne 0) {
            throw "Cannot inspect existing checkout: $checkoutPath"
        }
        if ($dirty) {
            throw "Refusing to overwrite dirty checkout: $checkoutPath"
        }
        $remote = git -C $checkoutPath remote get-url origin
        if ($LASTEXITCODE -ne 0 -or $remote.Trim() -ne $engine.checkoutSource) {
            throw "Existing checkout has a different origin: $checkoutPath"
        }
    } else {
        git init $checkoutPath | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not initialize source checkout: $checkoutPath"
        }
        git -C $checkoutPath remote add origin $engine.checkoutSource
        if ($LASTEXITCODE -ne 0) {
            throw "Could not add source remote for $($engine.id)"
        }
    }

    $localFetchRef = "refs/frostkeys-source-lock/$($engine.id)"
    git -C $checkoutPath fetch --depth 1 origin "+$($engine.fetchRef):$localFetchRef" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not fetch locked $($engine.id) ref $($engine.fetchRef)"
    }
    if ($engine.fetchRef -like 'refs/tags/*') {
        $refObject = (git -C $checkoutPath rev-parse $localFetchRef).Trim().ToLowerInvariant()
        if ($LASTEXITCODE -ne 0 -or $refObject -ne $engine.commit) {
            throw "Tag object verification failed for $($engine.id): expected $($engine.commit), got $refObject"
        }
    }
    git -C $checkoutPath checkout --detach --force $engine.checkoutCommit | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not check out locked $($engine.id) commit $($engine.checkoutCommit)"
    }
    $actual = (git -C $checkoutPath rev-parse HEAD).Trim().ToLowerInvariant()
    if ($LASTEXITCODE -ne 0 -or $actual -ne $engine.checkoutCommit) {
        throw "Commit verification failed for $($engine.id): expected $($engine.checkoutCommit), got $actual"
    }

    # librime's build inputs are recursive submodules.  The source verifier intentionally rejects
    # uninitialized or drifted gitlinks, so acquisition must materialize them at the exact commits
    # recorded by the locked superproject rather than leaving a checkout that can never be built.
    # Run this for every engine: a future locked Mozc submodule must receive the same treatment.
    git -C $checkoutPath submodule sync --recursive | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not synchronize locked submodule URLs for $($engine.id)"
    }
    git -C $checkoutPath submodule update --init --recursive --checkout | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not initialize locked submodules for $($engine.id)"
    }
    $submoduleStatus = @(git -C $checkoutPath submodule status --recursive)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect locked submodules for $($engine.id)"
    }
    foreach ($statusLine in $submoduleStatus) {
        if ([string]::IsNullOrWhiteSpace($statusLine)) {
            continue
        }
        # A leading '-' means uninitialized, and '+' means a revision differing from the pinned
        # superproject. Both must fail before a source tree can be treated as reproducible.
        if (-not $statusLine.StartsWith(' ')) {
            throw "Submodule is missing or differs from its locked revision for $($engine.id): $statusLine"
        }
    }
    $postCheckoutDirty = git -C $checkoutPath status --porcelain
    if ($LASTEXITCODE -ne 0 -or $postCheckoutDirty) {
        throw "Pinned checkout is unexpectedly dirty: $checkoutPath"
    }
    Write-Host "Verified $($engine.project) at $actual in $checkoutPath"
}
