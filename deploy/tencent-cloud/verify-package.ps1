#Requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$ArchivePath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ArchivePath))
try {
    $prefix = 'Psych_Counseling_Agent_Server/'
    $entries = [System.Collections.Generic.Dictionary[string, System.IO.Compression.ZipArchiveEntry]]::new([System.StringComparer]::Ordinal)
    foreach ($entry in $archive.Entries) {
        if (-not $entry.FullName.StartsWith($prefix) -or $entry.FullName.Contains('\') -or
            $entry.FullName.Split('/') -contains '..' -or $entries.ContainsKey($entry.FullName)) {
            throw "Unsafe or duplicate archive entry: $($entry.FullName)"
        }
        $entries[$entry.FullName] = $entry
        if ($entry.Name -eq '.env' -or $entry.Name -match '^application-local\.ya?ml$') {
            throw "Private runtime configuration in archive: $($entry.FullName)"
        }
    }
    $required = @('manage.sh', 'README_DEPLOY.md', 'nginx-site.conf.example',
        'dk-ai-agent/docker-compose.yml', 'dk-ai-agent/.env.example',
        'dk-ai-agent/.dockerignore', 'dk-ai-agent/Dockerfile',
        'dk-ai-agent/pom.xml', 'dk-ai-agent/mvnw',
        'ai-worker/.dockerignore', 'ai-worker/Dockerfile',
        'ai-worker/pyproject.toml', 'ai-worker/README.md',
        'dk-ai-agent/dk-ai-agent-frontend/.dockerignore',
        'dk-ai-agent/dk-ai-agent-frontend/Dockerfile',
        'dk-ai-agent/dk-ai-agent-frontend/package.json',
        'dk-ai-agent/dk-ai-agent-frontend/package-lock.json',
        'dk-ai-agent/dk-ai-agent-frontend/index.html',
        'dk-ai-agent/dk-ai-agent-frontend/nginx.conf',
        'dk-ai-agent/dk-ai-agent-frontend/security-headers.conf',
        'dk-ai-agent/dk-ai-agent-frontend/vite.config.js',
        'dk-ai-agent/dk-ai-agent-frontend/vitest.config.js',
        'dk-ai-agent/dk-ai-agent-frontend/eslint.config.js', 'PACKAGE_MANIFEST.json')
    foreach ($path in $required) {
        if (-not $entries.ContainsKey($prefix + $path)) { throw "Missing package dependency: $path" }
    }
    # ZIPs produced by build-package.ps1 contain files without directory entries.
    # Require a real file below each exact directory prefix; an empty directory
    # or a similarly named sibling (such as src-backup/) must not satisfy this.
    $requiredDirectories = @('dk-ai-agent/.mvn/', 'dk-ai-agent/src/',
        'dk-ai-agent/dk-ai-agent-frontend/src/', 'ai-worker/src/', 'counseling-kb/raw/')
    foreach ($path in $requiredDirectories) {
        $directoryPrefix = $prefix + $path
        $hasFile = $false
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.StartsWith($directoryPrefix, [System.StringComparison]::Ordinal) -and
                -not [string]::IsNullOrEmpty($entry.Name)) {
                $hasFile = $true
                break
            }
        }
        if (-not $hasFile) { throw "Missing or empty package directory: $path" }
    }
    $reader = [System.IO.StreamReader]::new($entries[$prefix + 'PACKAGE_MANIFEST.json'].Open(), [System.Text.Encoding]::UTF8)
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($entries.Count -ne $manifest.fileCount + 1) { throw 'Manifest count mismatch' }
    $seen = @{}
    foreach ($item in $manifest.files) {
        $name = $prefix + $item.path
        if ($seen.ContainsKey($name) -or -not $entries.ContainsKey($name)) { throw "Invalid manifest path: $name" }
        $seen[$name] = $true
        $entry = $entries[$name]
        $stream = $entry.Open()
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { $hash = [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose(); $stream.Dispose() }
        if ($entry.Length -ne $item.bytes -or $hash -ne $item.sha256) { throw "Payload mismatch: $name" }
    }
    Write-Output "Verified $($manifest.fileCount) payload files and required deployment/Docker/test inputs."
} finally { $archive.Dispose() }
