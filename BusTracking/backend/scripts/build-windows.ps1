param(
    [switch]$UseEnvironmentProxy,
    [string[]]$Goals = @('verify')
)
$ErrorActionPreference = 'Stop'
$backend = Split-Path $PSScriptRoot -Parent
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$version = (& $java -version 2>&1 | Out-String)
if ($version -notmatch 'version "(\d+)') { throw 'Java not found. Install JDK 21 and set JAVA_HOME.' }
if ([int]$Matches[1] -lt 21) { throw 'This project requires JDK 21 or newer. Set JAVA_HOME to JDK 21, then reopen PowerShell.' }
$tempSettings = $null
Push-Location $backend
try {
    $arguments = @('-U', '-B', '-ntp')
    if ($UseEnvironmentProxy) {
        $raw = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { $env:HTTP_PROXY }
        if (-not $raw) { throw 'Set HTTPS_PROXY or HTTP_PROXY before using -UseEnvironmentProxy.' }
        $proxy = [Uri]$raw
        if ($proxy.UserInfo) { throw 'For authenticated proxies, configure your private Maven settings.xml; credentials are not written by this script.' }
        $hostName = [System.Security.SecurityElement]::Escape($proxy.Host)
        $tempSettings = [System.IO.Path]::GetTempFileName()
        $settings = "<settings><proxies><proxy><id>environment</id><active>true</active><protocol>http</protocol><host>$hostName</host><port>$($proxy.Port)</port></proxy></proxies></settings>"
        Set-Content -Path $tempSettings -Value $settings -Encoding UTF8
        $arguments += @('-s', $tempSettings)
    }
    & .\mvnw.cmd @arguments @Goals
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE. Read the first ERROR above; see LIVE_TRANSIT.md for DNS, proxy, TLS and JDK checks." }
} finally {
    Pop-Location
    if ($tempSettings) { Remove-Item $tempSettings -ErrorAction SilentlyContinue }
}
