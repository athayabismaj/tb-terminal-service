[CmdletBinding()]
param(
    [string]$PropertiesFile = "local.properties"
)

$ErrorActionPreference = "Stop"
$properties = @{}
Get-Content -LiteralPath $PropertiesFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
        $properties[$matches[1].Trim()] = $matches[2].Trim()
    }
}

foreach ($required in @("DB_URL", "DB_USER", "DB_PASSWORD", "JWT_SECRET", "JWT_ISSUER", "JWT_AUDIENCE")) {
    if ([string]::IsNullOrWhiteSpace($properties[$required])) { throw "$required wajib tersedia di $PropertiesFile" }
}

$sourceUri = [Uri]($properties["DB_URL"] -replace '^jdbc:', '')
$databaseName = "tb_terminal_batch8_" + [Guid]::NewGuid().ToString("N").Substring(0, 12)
if ($databaseName -notmatch '^tb_terminal_batch8_[a-f0-9]{12}$') { throw "Nama database test tidak aman" }

$pgBin = if ($env:POSTGRES_BIN) { $env:POSTGRES_BIN } else { "C:\Program Files\PostgreSQL\18\bin" }
$createdb = Join-Path $pgBin "createdb.exe"
$dropdb = Join-Path $pgBin "dropdb.exe"
$backupRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) "tb-terminal-tests"))
$backupDirectory = [IO.Path]::GetFullPath((Join-Path $backupRoot ([Guid]::NewGuid().ToString("N"))))
if (-not $backupDirectory.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar)) { throw "Direktori sementara tidak aman" }

$env:PGPASSWORD = $properties["DB_PASSWORD"]
& $createdb --host=$($sourceUri.Host) --port=$($sourceUri.Port) --username=$($properties["DB_USER"]) $databaseName
if ($LASTEXITCODE -ne 0) { throw "Gagal membuat database test PostgreSQL" }
New-Item -ItemType Directory -Path $backupDirectory | Out-Null

try {
    $testUrl = "jdbc:postgresql://$($sourceUri.Host):$($sourceUri.Port)/$databaseName"
    $env:TEST_DB_URL = $testUrl
    $env:TEST_DB_USER = $properties["DB_USER"]
    $env:TEST_DB_PASSWORD = $properties["DB_PASSWORD"]
    $env:DB_URL = $testUrl
    $env:DB_USER = $properties["DB_USER"]
    $env:DB_PASSWORD = $properties["DB_PASSWORD"]
    $env:JWT_SECRET = $properties["JWT_SECRET"]
    $env:JWT_ISSUER = $properties["JWT_ISSUER"]
    $env:JWT_AUDIENCE = $properties["JWT_AUDIENCE"]
    $env:APP_ENV = "test"
    $env:BACKUP_DIRECTORY = $backupDirectory
    $env:BACKUP_ENABLED = "true"
    $env:RESTORE_ENABLED = "true"
    $env:PG_DUMP_EXECUTABLE = Join-Path $pgBin "pg_dump.exe"
    $env:PG_RESTORE_EXECUTABLE = Join-Path $pgBin "pg_restore.exe"

    & .\gradlew.bat test --no-daemon --no-configuration-cache --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "Test backend gagal dengan exit code $LASTEXITCODE" }
} finally {
    & $dropdb --host=$($sourceUri.Host) --port=$($sourceUri.Port) --username=$($properties["DB_USER"]) --if-exists --force $databaseName
    if (Test-Path -LiteralPath $backupDirectory) {
        $verified = [IO.Path]::GetFullPath($backupDirectory)
        if ($verified.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar)) {
            Remove-Item -LiteralPath $verified -Recurse -Force
        }
    }
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
