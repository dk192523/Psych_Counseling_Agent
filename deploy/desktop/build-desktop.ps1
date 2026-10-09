#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$OutputDirectory = '',
    [ValidateSet('Client', 'Server', 'Both')][string]$Product = 'Both',
    [string]$PythonExecutable = 'python.exe',
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '0.1.0',
    [switch]$Installer
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$ClientRoot = Join-Path $RepoRoot 'desktop-client'
$BackendRoot = Join-Path $RepoRoot 'dk-ai-agent'
$FrontendRoot = Join-Path $BackendRoot 'dk-ai-agent-frontend'
$WorkerRoot = Join-Path $RepoRoot 'ai-worker'
$TargetRoot = Join-Path $ClientRoot 'target'
$AppIcon = Join-Path $PSScriptRoot 'PsychCounselor.ico'
$SelectedProducts = if ($Product -eq 'Both') { @('Client', 'Server') } else { @($Product) }

function Invoke-BuildTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable 失败，退出码 $LASTEXITCODE" }
}

function Assert-FrontendNodeEngine {
    $packagePath = Join-Path $FrontendRoot 'package.json'
    $package = Get-Content -LiteralPath $packagePath -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
    if (!$package.ContainsKey('engines') -or !$package.engines.ContainsKey('node') -or
        [string]::IsNullOrWhiteSpace([string]$package.engines.node)) {
        throw "前端 package.json 缺少 engines.node：$packagePath"
    }
    $requiredRange = [string]$package.engines.node
    $nodeCommand = Get-Command 'node.exe' -ErrorAction SilentlyContinue
    if (!$nodeCommand) { throw "Client 构建需要 Node.js，前端要求 engines.node：$requiredRange。请安装符合要求的版本并加入 PATH。" }
    $nodeOutput = & $nodeCommand.Source --version
    if ($LASTEXITCODE -ne 0) { throw '无法读取本机 Node.js 版本，请检查 node.exe 是否可用。' }
    $nodeVersionText = ([string]$nodeOutput).Trim()
    if ($nodeVersionText -notmatch '^v?(\d+\.\d+\.\d+)$') {
        throw "无法识别本机 Node.js 稳定版本：$nodeVersionText。前端要求 engines.node：$requiredRange。"
    }
    $nodeVersion = [version]$Matches[1]
    $satisfied = $false
    # Support the frontend's full-version caret / minimum alternatives; fail closed if its syntax changes.
    foreach ($alternative in ($requiredRange -split '\|\|')) {
        if ($alternative.Trim() -notmatch '^(\^|>=)\s*(\d+\.\d+\.\d+)$') {
            throw "无法解析前端 engines.node：$requiredRange。构建脚本目前支持 ^x.y.z、>=x.y.z 及其 || 组合。"
        }
        $operator = $Matches[1]
        $minimum = [version]$Matches[2]
        if ($operator -eq '>=') {
            if ($nodeVersion -ge $minimum) { $satisfied = $true }
        } else {
            $upperBound = if ($minimum.Major -gt 0) {
                [version]::new($minimum.Major + 1, 0, 0)
            } elseif ($minimum.Minor -gt 0) {
                [version]::new(0, $minimum.Minor + 1, 0)
            } else {
                [version]::new(0, 0, $minimum.Build + 1)
            }
            if ($nodeVersion -ge $minimum -and $nodeVersion -lt $upperBound) { $satisfied = $true }
        }
    }
    if (!$satisfied) {
        throw "Client 构建已停止：本机 Node.js $nodeVersionText 不满足前端 engines.node：$requiredRange。请切换到符合要求的 Node.js 后重新构建；尚未执行 npm ci。"
    }
}

function Assert-SafeOutputPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path).TrimEnd([char[]]'\/')
    if ($full -eq [IO.Path]::GetPathRoot($full).TrimEnd([char[]]'\/') -or $full -eq $RepoRoot) {
        throw '输出目录不能是磁盘根目录或仓库根目录。'
    }
    foreach ($source in @('desktop-client', 'dk-ai-agent', 'ai-worker', 'deploy', '.github', '.git', '.agents', '.codex')) {
        $protected = Join-Path $RepoRoot $source
        if ($full -eq $protected -or $full.StartsWith($protected + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "输出目录不能写入源码或构建目录：$protected"
        }
    }
    # GetFullPath alone does not protect against junctions pointing into source directories.
    $ancestor = $full
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if (!$item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw "输出路径不能经过文件、符号链接或目录联接：$ancestor"
            }
        }
        $ancestor = Split-Path -Parent $ancestor
    }
}

function Copy-AllowlistedClasses([string]$ClassesRoot, [string]$StageRoot, [string[]]$ClassNames) {
    $package = Join-Path $ClassesRoot 'com/dk/psychclient'
    foreach ($name in $ClassNames) {
        if (!(Test-Path -LiteralPath (Join-Path $package ($name + '.class')) -PathType Leaf)) { throw "缺少发行依赖类：$name" }
        foreach ($file in @(Get-ChildItem -LiteralPath $package -File -Filter '*.class' | Where-Object {
            $_.Name -eq ($name + '.class') -or $_.Name.StartsWith($name + '$', [StringComparison]::Ordinal)
        })) {
            $destination = Join-Path $StageRoot ('com/dk/psychclient/' + $file.Name)
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $destination
        }
    }
    $icons = Join-Path $StageRoot 'icons'
    New-Item -ItemType Directory -Path $icons -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $ClassesRoot 'icons/logo.png') -Destination $icons
}

function Write-ClasspathManifest([string]$Path, [string[]]$Entries) {
    # URI escaped dependency names are ASCII; folding at columns also folds at byte boundaries.
    $line = 'Class-Path: ' + [string]::Join(' ', $Entries)
    $folded = [Text.StringBuilder]::new()
    [void]$folded.Append("Manifest-Version: 1.0`r`n")
    while ($line.Length -gt 70) {
        [void]$folded.Append($line.Substring(0, 70)).Append("`r`n")
        $line = ' ' + $line.Substring(70)
    }
    [void]$folded.Append($line).Append("`r`n`r`n")
    [IO.File]::WriteAllText($Path, $folded.ToString(), [Text.UTF8Encoding]::new($false))
}

if ($env:OS -ne 'Windows_NT') { throw '此脚本需要 Windows x64 构建主机。' }
if (!(Test-Path -LiteralPath $AppIcon -PathType Leaf)) { throw '缺少 deploy/desktop/PsychCounselor.ico。' }
if (!$JdkHome) { throw '请用 -JdkHome 指向完整 Windows x64 JDK 25。' }
$JdkHome = [IO.Path]::GetFullPath($JdkHome)
foreach ($toolName in @('java.exe', 'jar.exe', 'jlink.exe', 'jpackage.exe')) {
    if (!(Test-Path -LiteralPath (Join-Path $JdkHome "bin/$toolName") -PathType Leaf)) { throw "JDK 缺少 $toolName。" }
}
if (!(Test-Path -LiteralPath (Join-Path $JdkHome 'jmods') -PathType Container)) { throw 'JDK 缺少 jmods。' }
$JdkRelease = [IO.File]::ReadAllText((Join-Path $JdkHome 'release'), [Text.Encoding]::UTF8)
if ($JdkRelease -notmatch '(?m)^JAVA_VERSION="25(?:\.|\+|"|-)') { throw '桌面发行包要求 JDK 25。' }
if ($JdkRelease -notmatch '(?m)^OS_ARCH="(?:amd64|x86_64)"') { throw 'JavaFX win 工件要求 x64 JDK。' }
$JavaVersion = [regex]::Match($JdkRelease, '(?m)^JAVA_VERSION="([^"]+)"').Groups[1].Value
$JavaArchitecture = [regex]::Match($JdkRelease, '(?m)^OS_ARCH="([^"]+)"').Groups[1].Value
$protocolSource = [IO.File]::ReadAllText((Join-Path $ClientRoot 'src/main/java/com/dk/psychclient/LocalServerEndpoint.java'), [Text.Encoding]::UTF8)
$protocolMatch = [regex]::Match($protocolSource, 'PROTOCOL_VERSION\s*=\s*(\d+)\s*;')
if (!$protocolMatch.Success) { throw '无法读取本地端点协议版本。' }
$ProtocolVersion = [int]$protocolMatch.Groups[1].Value
if (!$OutputDirectory) { $OutputDirectory = Join-Path $RepoRoot 'release/desktop' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
Assert-SafeOutputPath $OutputDirectory
foreach ($selected in $SelectedProducts) {
    $destination = Join-Path $OutputDirectory $selected.ToLowerInvariant()
    Assert-SafeOutputPath $destination
    if (Test-Path -LiteralPath $destination) { throw "输出已存在：$destination。请指定新的 -OutputDirectory，脚本不会覆盖或删除旧产物。" }
}
if ('Client' -in $SelectedProducts) {
    if (!(Get-Command 'npm.cmd' -ErrorAction SilentlyContinue)) { throw 'Client 构建需要符合前端 engines 的 Node/npm。' }
    Assert-FrontendNodeEngine
}
if ('Server' -in $SelectedProducts -and !(Get-Command $PythonExecutable -ErrorAction SilentlyContinue)) {
    throw 'Server 构建需要 Python 3.12+ 冻结 Worker；用 -PythonExecutable 指定构建机 Python。'
}
if ($Installer) {
    $hasWix = (Get-Command 'wix.exe' -ErrorAction SilentlyContinue) -or (
        (Get-Command 'candle.exe' -ErrorAction SilentlyContinue) -and (Get-Command 'light.exe' -ErrorAction SilentlyContinue))
    if (!$hasWix) { throw 'EXE 安装包需要与 JDK 25 jpackage 兼容的 WiX 工具集并加入 PATH。' }
}

$SavedJavaHome = $env:JAVA_HOME
$SavedPath = $env:PATH
$PackagingRoot = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath([IO.Path]::GetTempPath()))
$WorkRoot = $null
$CreatedWorkRoot = $null
$BuildError = $null
try {
    $env:JAVA_HOME = $JdkHome
    $env:PATH = "$(Join-Path $JdkHome 'bin');$SavedPath"
    Push-Location -LiteralPath $ClientRoot
    try { Invoke-BuildTool (Join-Path $ClientRoot 'mvnw.cmd') @('clean', 'package', '-Dmaven.test.skip=true') }
    finally { Pop-Location }
    # Keep Python dependency paths short even when Windows long paths are disabled.
    # Use a fresh GUID directly under TEMP; finally only removes this invocation's directory.
    $ancestor = $PackagingRoot
    while ($ancestor) {
        $item = Get-Item -LiteralPath $ancestor -Force
        if (!$item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "staging 路径不能经过文件、符号链接或目录联接：$ancestor"
        }
        $ancestor = Split-Path -Parent $ancestor
    }
    $WorkRoot = Join-Path $PackagingRoot ([guid]::NewGuid().ToString('N'))
    $CreatedWorkRoot = (New-Item -ItemType Directory -Path $WorkRoot).FullName
    $CommonClasses = @('DesktopTray', 'ClientSettings', 'ServerAddress', 'LocalServerEndpoint')
    $ClientClasses = @('DesktopLauncher', 'PsychCounselorClient', 'ClientUiHost', 'ClientUpstreamTransport', 'ClientApiRoutes', 'DesktopPageBridge') + $CommonClasses
    $ServerClasses = @('LocalServerLauncher', 'LocalServerManager', 'LocalBackendHost', 'WorkerSidecar', 'DpapiSecretStore', 'UserAutoStart', 'AutoStartPreference', 'LocalDataBackup') + $CommonClasses

    foreach ($selected in $SelectedProducts) {
        $isClient = $selected -eq 'Client'
        $name = 'PsychCounselor' + $selected
        $destination = Join-Path $OutputDirectory $selected.ToLowerInvariant()
        $productStage = Join-Path $WorkRoot $selected.ToLowerInvariant()
        $inputRoot = Join-Path $productStage 'input'
        $classesStage = Join-Path $productStage 'classes'
        $libRoot = Join-Path $inputRoot 'lib/desktop'
        New-Item -ItemType Directory -Path $libRoot -Force | Out-Null
        $allowedClasses = if ($isClient) { $ClientClasses } else { $ServerClasses }
        Copy-AllowlistedClasses (Join-Path $TargetRoot 'classes') $classesStage $allowedClasses
        $classPath = [Collections.Generic.List[string]]::new()
        foreach ($dependency in @(Get-ChildItem -LiteralPath (Join-Path $TargetRoot 'client-libs') -Filter '*.jar' -File | Sort-Object Name)) {
            if ($dependency.Name -notmatch '^jna(?:-platform)?-[\d.]+\.jar$') { throw "未允许的桌面依赖：$($dependency.Name)" }
            Copy-Item -LiteralPath $dependency.FullName -Destination $libRoot
            $classPath.Add('lib/desktop/' + [uri]::EscapeDataString($dependency.Name))
        }
        if ($isClient) {
            $frontendDist = Join-Path $productStage 'frontend-dist'
            Push-Location -LiteralPath $FrontendRoot
            try {
                Invoke-BuildTool 'npm.cmd' @('ci')
                Invoke-BuildTool 'npm.cmd' @('run', 'build', '--', '--outDir', $frontendDist, '--emptyOutDir')
            } finally { Pop-Location }
            if (!(Test-Path -LiteralPath (Join-Path $frontendDist 'index.html') -PathType Leaf)) { throw 'Vite 未生成 index.html。' }
            $uiRoot = Join-Path $classesStage 'static'
            New-Item -ItemType Directory -Path $uiRoot | Out-Null
            foreach ($asset in @(Get-ChildItem -LiteralPath $frontendDist -Recurse -File)) {
                if ($asset.Extension -notin @('.html', '.js', '.css', '.png', '.jpg', '.jpeg', '.svg', '.ico', '.webp', '.gif', '.woff', '.woff2', '.ttf')) { throw "未允许的前端资源：$($asset.Name)" }
                $relative = $asset.FullName.Substring($frontendDist.Length + 1)
                $assetTarget = Join-Path $uiRoot $relative
                New-Item -ItemType Directory -Path (Split-Path -Parent $assetTarget) -Force | Out-Null
                Copy-Item -LiteralPath $asset.FullName -Destination $assetTarget
            }
        } else {
            $backendLibRoot = Join-Path $inputRoot 'lib/backend'
            New-Item -ItemType Directory -Path $backendLibRoot | Out-Null
            Push-Location -LiteralPath $BackendRoot
            try {
                Invoke-BuildTool (Join-Path $BackendRoot 'mvnw.cmd') @('clean', 'compile',
                    'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
                    '-Dmaven.test.skip=true', '-DincludeScope=runtime', "-DoutputDirectory=$backendLibRoot")
            } finally { Pop-Location }
            $backendClasses = Join-Path $BackendRoot 'target/classes'
            $backendStage = Join-Path $productStage 'backend-classes'
            New-Item -ItemType Directory -Path $backendStage | Out-Null
            foreach ($resource in @(Get-ChildItem -LiteralPath $backendClasses -Recurse -File)) {
                $relative = $resource.FullName.Substring($backendClasses.Length + 1).Replace('\', '/')
                $allowed = $relative.StartsWith('com/dk/dkaiagent/', [StringComparison]::Ordinal) -and $resource.Extension -eq '.class'
                $allowed = $allowed -or $relative -in @('application.yml', 'application-local-desktop.yml')
                $allowed = $allowed -or ($relative.StartsWith('document/') -and $resource.Extension -in @('.txt', '.md', '.pdf'))
                $allowed = $allowed -or ($relative.StartsWith('exam/') -and $resource.Extension -in @('.json', '.jsonl', '.txt', '.md'))
                if (!$allowed) { continue }
                if ($relative -in @('application.yml', 'application-local-desktop.yml')) {
                    $config = [IO.File]::ReadAllText($resource.FullName, [Text.Encoding]::UTF8)
                    $config = $config.Replace('sk-replace-with-your-deepseek-key', '')
                    if ($config -match '(?i)\bsk-[a-z0-9_-]{20,}\b|-----BEGIN .*PRIVATE KEY-----') { throw "发行配置疑似含真实密钥：$relative" }
                }
                $resourceTarget = Join-Path $backendStage $relative
                New-Item -ItemType Directory -Path (Split-Path -Parent $resourceTarget) -Force | Out-Null
                Copy-Item -LiteralPath $resource.FullName -Destination $resourceTarget
            }
            Invoke-BuildTool (Join-Path $JdkHome 'bin/jar.exe') @('--create', '--file', (Join-Path $inputRoot 'server-backend-classes.jar'), '-C', $backendStage, '.')
            $classPath.Add('server-backend-classes.jar')
            foreach ($dependency in @(Get-ChildItem -LiteralPath $backendLibRoot -Filter '*.jar' -File | Sort-Object Name)) { $classPath.Add('lib/backend/' + [uri]::EscapeDataString($dependency.Name)) }

            # Freeze a sidecar from staged sources. The venv stays on the build machine, outside jpackage input.
            $workerSource = Join-Path $productStage 'worker-source'
            $workerPackage = Join-Path $workerSource 'src/dk_ai_worker'
            New-Item -ItemType Directory -Path $workerPackage -Force | Out-Null
            foreach ($file in @('pyproject.toml', 'README.md')) { Copy-Item -LiteralPath (Join-Path $WorkerRoot $file) -Destination $workerSource }
            foreach ($file in @(Get-ChildItem -LiteralPath (Join-Path $WorkerRoot 'src/dk_ai_worker') -File -Filter '*.py')) { Copy-Item -LiteralPath $file.FullName -Destination $workerPackage }
            $workerEntry = Join-Path $workerSource 'desktop_worker.py'
            $entrySource = @'
import multiprocessing
import os
import uvicorn
from dk_ai_worker.config import Settings
from dk_ai_worker.main import create_app

if __name__ == "__main__":
    multiprocessing.freeze_support()
    port = int(os.environ["AI_WORKER_PORT"])
    if not 1 <= port <= 65535:
        raise ValueError("AI_WORKER_PORT is out of range")
    def request_shutdown():
        server.should_exit = True

    # Never load a working-directory .env in the installed application.
    app = create_app(Settings(_env_file=None), request_shutdown=request_shutdown)
    server = uvicorn.Server(uvicorn.Config(
        app, host="127.0.0.1", port=port, loop="asyncio", http="h11", ws="none",
        log_config=None, access_log=False))
    server.run()
'@
            [IO.File]::WriteAllText($workerEntry, $entrySource, [Text.UTF8Encoding]::new($false))
            Invoke-BuildTool $PythonExecutable @('-c', 'import sys; assert sys.version_info >= (3, 12), "Python 3.12+ required"')
            $workerVenv = Join-Path $productStage 'worker-build-venv'
            Invoke-BuildTool $PythonExecutable @('-m', 'venv', $workerVenv)
            $buildPython = Join-Path $workerVenv 'Scripts/python.exe'
            Invoke-BuildTool $buildPython @('-m', 'pip', 'install', '--disable-pip-version-check', $workerSource, 'pyinstaller>=6,<7')
            Invoke-BuildTool $buildPython @('-m', 'PyInstaller', '--noconfirm', '--clean', '--onedir', '--noconsole',
                '--name', 'PsychCounselorWorker', '--distpath', (Join-Path $productStage 'worker-dist'),
                '--workpath', (Join-Path $productStage 'worker-build'), '--specpath', $productStage,
                '--collect-submodules', 'uvicorn', '--collect-data', 'jieba', $workerEntry)
            Copy-Item -LiteralPath (Join-Path $productStage 'worker-dist/PsychCounselorWorker') -Destination (Join-Path $inputRoot 'worker') -Recurse
        }
        $mainJar = if ($isClient) { 'psych-counselor-client.jar' } else { 'psych-counselor-server.jar' }
        $mainClass = if ($isClient) { 'com.dk.psychclient.DesktopLauncher' } else { 'com.dk.psychclient.LocalServerLauncher' }
        $classpathManifest = Join-Path $productStage 'classpath.mf'
        Write-ClasspathManifest $classpathManifest $classPath.ToArray()
        Invoke-BuildTool (Join-Path $JdkHome 'bin/jar.exe') @('--create', '--file', (Join-Path $inputRoot $mainJar), '--manifest', $classpathManifest, '-C', $classesStage, '.')
        $runtimeRoot = Join-Path $productStage 'runtime'
        $runtimeModules = if ($isClient) {
            'javafx.controls,javafx.web,java.desktop,java.net.http,jdk.httpserver,jdk.crypto.ec,jdk.localedata,jdk.unsupported'
        } else {
            # Spring/JDBC/JNA/ONNX use reflection, management, naming and service providers.
            'javafx.controls,java.desktop,java.net.http,jdk.httpserver,java.sql,java.naming,java.management,java.instrument,java.security.jgss,java.security.sasl,java.rmi,java.compiler,jdk.management,jdk.crypto.ec,jdk.localedata,jdk.unsupported,jdk.zipfs'
        }
        Invoke-BuildTool (Join-Path $JdkHome 'bin/jlink.exe') @('--module-path', "$(Join-Path $JdkHome 'jmods');$(Join-Path $TargetRoot 'javafx-modules')",
            '--add-modules', $runtimeModules, '--bind-services', '--output', $runtimeRoot, '--strip-debug', '--no-header-files', '--no-man-pages')
        New-Item -ItemType Directory -Path $destination | Out-Null
        $nativeModules = if ($isClient) { 'ALL-UNNAMED,javafx.graphics,javafx.media,javafx.web' } else { 'ALL-UNNAMED,javafx.graphics' }
        Invoke-BuildTool (Join-Path $JdkHome 'bin/jpackage.exe') @('--type', 'app-image', '--name', $name, '--app-version', $Version,
            '--vendor', 'PsychCounselor', '--description', $(if ($isClient) { 'AI 心理咨询桌面客户端' } else { 'AI 心理咨询本地后台服务' }),
            '--input', $inputRoot, '--main-jar', $mainJar, '--main-class', $mainClass, '--icon', $AppIcon,
            '--runtime-image', $runtimeRoot, '--dest', $destination, '--java-options', ('--add-modules=' + $runtimeModules),
            '--java-options', ('--enable-native-access=' + $nativeModules), '--java-options', '-Dfile.encoding=UTF-8')
        $manifest = [ordered]@{
            product = $name; version = $Version; protocolVersion = $ProtocolVersion; architecture = 'windows-x64'
            processArchitecture = $(if ($isClient) { 'desktop-client-jvm' } else { 'spring-server-jvm-with-python-worker-sidecar' })
            builtAtUtc = [DateTime]::UtcNow.ToString('o'); javaVersion = $JavaVersion; javaArchitecture = $JavaArchitecture
            mainClass = $mainClass; launcher = $name + '.exe'; runtimeModules = $runtimeModules.Split(',')
            packagedClasses = $allowedClasses; workerBundled = !$isClient; workerAutoStartIntegrated = !$isClient
            clientPomSha256 = (Get-FileHash -LiteralPath (Join-Path $ClientRoot 'pom.xml') -Algorithm SHA256).Hash
            backendPomSha256 = $(if (!$isClient) { (Get-FileHash -LiteralPath (Join-Path $BackendRoot 'pom.xml') -Algorithm SHA256).Hash } else { $null })
            frontendLockSha256 = $(if ($isClient) { (Get-FileHash -LiteralPath (Join-Path $FrontendRoot 'package-lock.json') -Algorithm SHA256).Hash } else { $null })
            packagedFiles = @(Get-ChildItem -LiteralPath $inputRoot -Recurse -File | Sort-Object FullName | ForEach-Object {
                [ordered]@{ file = $_.FullName.Substring($inputRoot.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
            })
        }
        $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'build-manifest.json') -Encoding UTF8
        $appImage = Join-Path $destination $name
        Write-Host "app-image 已生成：$(Join-Path $appImage ($name + '.exe'))"
        if ($Installer) {
            $upgradeUuid = if ($isClient) { 'ad5a76a1-31db-4b31-b5eb-3cbab1eff0e8' } else { 'ae3ec3b4-4490-4989-b678-b0bba167d3f7' }
            Invoke-BuildTool (Join-Path $JdkHome 'bin/jpackage.exe') @('--type', 'exe', '--name', $name, '--app-version', $Version,
                '--app-image', $appImage, '--dest', $destination, '--icon', $AppIcon, '--install-dir', $name,
                '--win-upgrade-uuid', $upgradeUuid, '--win-menu-group', $name,
                '--win-per-user-install', '--win-menu', '--win-shortcut', '--win-dir-chooser')
        }
    }
} catch {
    $BuildError = $_
    throw
} finally {
    try {
        if ($null -ne $CreatedWorkRoot) {
            $cleanupPath = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($CreatedWorkRoot))
            $expectedPath = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($WorkRoot))
            $stagingGuid = [guid]::Empty
            if (![string]::Equals($cleanupPath, $expectedPath, [StringComparison]::OrdinalIgnoreCase) -or
                ![string]::Equals((Split-Path -Parent $cleanupPath), $PackagingRoot, [StringComparison]::OrdinalIgnoreCase) -or
                ![guid]::TryParseExact((Split-Path -Leaf $cleanupPath), 'N', [ref]$stagingGuid)) {
                throw "拒绝清理非本次创建的 GUID staging 目录：$cleanupPath"
            }
            if (Test-Path -LiteralPath $cleanupPath) {
                # Check every ancestor too: a junction in TEMP could redirect deletion.
                $ancestor = $cleanupPath
                while ($ancestor) {
                    $item = Get-Item -LiteralPath $ancestor -Force
                    if (!$item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                        throw "拒绝清理经过文件、符号链接或目录联接的 staging：$ancestor"
                    }
                    $ancestor = Split-Path -Parent $ancestor
                }
                Remove-Item -LiteralPath $cleanupPath -Recurse -Force
            }
        }
    } catch {
        if ($null -eq $BuildError) { throw }
        # Keep the original build failure while making incomplete cleanup visible.
        Write-Warning -Message "staging 清理失败：$($_.Exception.Message)" -WarningAction Continue
    } finally {
        $env:JAVA_HOME = $SavedJavaHome
        $env:PATH = $SavedPath
    }
}
