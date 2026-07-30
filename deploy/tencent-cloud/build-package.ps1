#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$OutputDirectory = "",
    [switch]$KeepStage
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $ReleaseRoot = Join-Path $RepoRoot "release"
} else {
    $ReleaseRoot = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDirectory))
}

$StageDirectory = Join-Path $ReleaseRoot ".package-stage"
$PackageName = "Psych_Counseling_Agent_Server"
$PackageRoot = Join-Path $StageDirectory $PackageName
$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$ArchiveName = "Psych_Counseling_Agent_TencentCloud_3004_$Timestamp.zip"
$ArchivePath = Join-Path $ReleaseRoot $ArchiveName
$ChecksumPath = "$ArchivePath.sha256"

function Assert-ChildPath {
    param(
        [Parameter(Mandatory = $true)][string]$Child,
        [Parameter(Mandatory = $true)][string]$Parent
    )

    $normalizedChild = [System.IO.Path]::GetFullPath($Child)
    $normalizedParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $normalizedChild.StartsWith($normalizedParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the release directory: $normalizedChild"
    }
}

function Copy-RequiredEntry {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    if (-not (Test-Path -LiteralPath $Source)) {
        throw "Required package input is missing: $Source"
    }
    $parent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

function Copy-OptionalEntry {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    if (Test-Path -LiteralPath $Source) {
        Copy-RequiredEntry -Source $Source -Destination $Destination
    }
}

New-Item -ItemType Directory -Force -Path $ReleaseRoot | Out-Null
Assert-ChildPath -Child $StageDirectory -Parent $ReleaseRoot
if (Test-Path -LiteralPath $StageDirectory) {
    Remove-Item -LiteralPath $StageDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $PackageRoot | Out-Null

$BackendSource = Join-Path $RepoRoot "dk-ai-agent"
$BackendTarget = Join-Path $PackageRoot "dk-ai-agent"
$BackendEntries = @(
    ".dockerignore",
    ".mvn",
    "Dockerfile",
    "docker-compose.yml",
    "mvnw",
    "mvnw.cmd",
    "pom.xml",
    "src"
)
foreach ($entry in $BackendEntries) {
    Copy-RequiredEntry -Source (Join-Path $BackendSource $entry) -Destination (Join-Path $BackendTarget $entry)
}

$FrontendSource = Join-Path $BackendSource "dk-ai-agent-frontend"
$FrontendTarget = Join-Path $BackendTarget "dk-ai-agent-frontend"
$FrontendEntries = @(
    ".dockerignore",
    "Dockerfile",
    "index.html",
    "nginx.conf",
    "package.json",
    "package-lock.json",
    "src",
    "vite.config.js"
)
foreach ($entry in $FrontendEntries) {
    Copy-RequiredEntry -Source (Join-Path $FrontendSource $entry) -Destination (Join-Path $FrontendTarget $entry)
}
Copy-OptionalEntry -Source (Join-Path $FrontendSource "public") -Destination (Join-Path $FrontendTarget "public")

$WorkerSource = Join-Path $RepoRoot "ai-worker"
$WorkerTarget = Join-Path $PackageRoot "ai-worker"
$WorkerEntries = @(
    ".dockerignore",
    "Dockerfile",
    "README.md",
    "pyproject.toml",
    "src"
)
foreach ($entry in $WorkerEntries) {
    Copy-RequiredEntry -Source (Join-Path $WorkerSource $entry) -Destination (Join-Path $WorkerTarget $entry)
}

Copy-RequiredEntry -Source (Join-Path $RepoRoot "counseling-kb\raw") -Destination (Join-Path $PackageRoot "counseling-kb\raw")
Copy-RequiredEntry -Source (Join-Path $PSScriptRoot ".env.server.example") -Destination (Join-Path $BackendTarget ".env.example")
Copy-RequiredEntry -Source (Join-Path $PSScriptRoot "manage.sh") -Destination (Join-Path $PackageRoot "manage.sh")
Copy-RequiredEntry -Source (Join-Path $PSScriptRoot "nginx-site.conf.example") -Destination (Join-Path $PackageRoot "nginx-site.conf.example")
Copy-RequiredEntry -Source (Join-Path $PSScriptRoot "DEPLOY_TENCENT_CLOUD.md") -Destination (Join-Path $PackageRoot "README_DEPLOY.md")

# Source directories can contain caches generated after tests. Remove only known
# generated artifacts inside the isolated package stage before hashing or zipping.
$ExcludedDirectoryNames = @(".git", ".idea", ".pytest_cache", ".venv", "__pycache__", "dist", "node_modules", "target")
$GeneratedDirectories = @(Get-ChildItem -LiteralPath $PackageRoot -Recurse -Force -Directory |
    Where-Object { $ExcludedDirectoryNames -contains $_.Name } |
    Sort-Object { $_.FullName.Length } -Descending)
foreach ($directory in $GeneratedDirectories) {
    Assert-ChildPath -Child $directory.FullName -Parent $PackageRoot
    Remove-Item -LiteralPath $directory.FullName -Recurse -Force
}

$GeneratedFiles = @(Get-ChildItem -LiteralPath $PackageRoot -Recurse -Force -File |
    Where-Object {
        $_.Name -like "*.pyc" -or
        $_.Name -like "*.pyo" -or
        $_.Name -like "*.log" -or
        $_.Name -like "*.err.log"
    })
foreach ($file in $GeneratedFiles) {
    Assert-ChildPath -Child $file.FullName -Parent $PackageRoot
    Remove-Item -LiteralPath $file.FullName -Force
}

$UnexpectedEnvFiles = @(Get-ChildItem -LiteralPath $PackageRoot -Recurse -Force -File |
    Where-Object { $_.Name -eq ".env" })
if ($UnexpectedEnvFiles.Count -gt 0) {
    throw "A real .env file was copied into the package. Packaging aborted."
}

$TextExtensions = @(".conf", ".env", ".example", ".java", ".js", ".json", ".md", ".properties", ".ps1", ".py", ".sh", ".toml", ".txt", ".vue", ".xml", ".yml", ".yaml")
$SecretCandidates = @(Get-ChildItem -LiteralPath $PackageRoot -Recurse -Force -File |
    Where-Object { $TextExtensions -contains $_.Extension.ToLowerInvariant() } |
    Select-String -Pattern 'sk-[A-Za-z0-9._-]{20,}' -Encoding UTF8)
if ($SecretCandidates.Count -gt 0) {
    $CandidateFiles = $SecretCandidates | ForEach-Object { $_.Path } | Sort-Object -Unique
    throw "Possible API key detected in package input: $($CandidateFiles -join ', ')"
}

$PayloadFiles = @(Get-ChildItem -LiteralPath $PackageRoot -Recurse -Force -File | Sort-Object FullName)
$ManifestEntries = foreach ($file in $PayloadFiles) {
    [ordered]@{
        path = $file.FullName.Substring($PackageRoot.Length + 1).Replace('\', '/')
        bytes = $file.Length
        sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$Manifest = [ordered]@{
    package = $PackageName
    target = "Tencent Cloud Linux / Docker Compose v2"
    targetPort = 3004
    createdAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    encoding = "UTF-8"
    manifestExcludesItself = $true
    fileCount = $PayloadFiles.Count
    files = $ManifestEntries
}
$ManifestJson = $Manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText((Join-Path $PackageRoot "PACKAGE_MANIFEST.json"), $ManifestJson + "`n", $Utf8NoBom)

if (Test-Path -LiteralPath $ArchivePath) {
    throw "Archive already exists: $ArchivePath"
}
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$ArchiveFiles = @(Get-ChildItem -LiteralPath $StageDirectory -Recurse -Force -File | Sort-Object FullName)
$ArchiveStream = [System.IO.File]::Open($ArchivePath, [System.IO.FileMode]::CreateNew)
try {
    $ZipArchive = [System.IO.Compression.ZipArchive]::new(
        $ArchiveStream,
        [System.IO.Compression.ZipArchiveMode]::Create,
        $false,
        [System.Text.Encoding]::UTF8
    )
    try {
        foreach ($file in $ArchiveFiles) {
            # PowerShell 5.1/.NET Framework CreateFromDirectory can store '\\' in entry
            # names. Linux ZIP tools require the specification's '/' path separator.
            $EntryName = $file.FullName.Substring($StageDirectory.Length + 1).Replace('\', '/')
            $Entry = $ZipArchive.CreateEntry($EntryName, [System.IO.Compression.CompressionLevel]::Optimal)
            $Entry.LastWriteTime = [System.DateTimeOffset]$file.LastWriteTime
            $EntryStream = $Entry.Open()
            $SourceStream = [System.IO.File]::OpenRead($file.FullName)
            try {
                $SourceStream.CopyTo($EntryStream)
            } finally {
                $SourceStream.Dispose()
                $EntryStream.Dispose()
            }
        }
    } finally {
        $ZipArchive.Dispose()
    }
} finally {
    $ArchiveStream.Dispose()
}

$ReadArchive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
try {
    $BadEntryNames = @($ReadArchive.Entries | Where-Object { $_.FullName.Contains('\') })
    if ($BadEntryNames.Count -gt 0) {
        throw "ZIP contains Windows path separators: $($BadEntryNames.FullName -join ', ')"
    }
    if ($ReadArchive.Entries.Count -ne $ArchiveFiles.Count) {
        throw "ZIP entry count mismatch: actual=$($ReadArchive.Entries.Count), expected=$($ArchiveFiles.Count)"
    }
} finally {
    $ReadArchive.Dispose()
}

$ArchiveHash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText($ChecksumPath, "$ArchiveHash  $ArchiveName`n", $Utf8NoBom)

if (-not $KeepStage) {
    Assert-ChildPath -Child $StageDirectory -Parent $ReleaseRoot
    Remove-Item -LiteralPath $StageDirectory -Recurse -Force
}

$ArchiveInfo = Get-Item -LiteralPath $ArchivePath
Write-Host "Package:  $($ArchiveInfo.FullName)"
Write-Host "Bytes:    $($ArchiveInfo.Length)"
Write-Host "SHA-256:  $ArchiveHash"
Write-Host "Checksum: $ChecksumPath"
