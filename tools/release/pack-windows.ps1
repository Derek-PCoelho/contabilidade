param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [ValidateSet("beta", "stable")]
    [string]$Maturity,
    [Parameter(Mandatory = $true)]
    [ValidateSet("validation", "signed")]
    [string]$Mode
)

$ErrorActionPreference = "Stop"
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$') {
    throw "Versão inválida: use SemVer com três componentes."
}

if ($Maturity -eq "stable" -and $Mode -ne "signed") {
    throw "O canal stable exige assinatura Windows."
}

if ($Maturity -eq "stable" -and $Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw "O canal stable exige uma versão final sem sufixo ou metadata."
}

if ($Mode -eq "signed" -and
    [string]::IsNullOrWhiteSpace($env:VPK_SIGN_PARAMS) -and
    [string]::IsNullOrWhiteSpace($env:VPK_AZURE_TRUSTED_SIGN_FILE)) {
    throw "Configure VPK_SIGN_PARAMS ou VPK_AZURE_TRUSTED_SIGN_FILE no ambiente protegido."
}

$releaseRepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
$releaseRid = "win-x64"
$releaseChannel = "$releaseRid-$Maturity"
$releasePublishDir = Join-Path $releaseRepoRoot "artifacts/phase10/publish/$releaseChannel/$Version"
$releaseOutputDir = Join-Path $releaseRepoRoot "artifacts/phase10/$releaseChannel/$Version"
if ((Test-Path $releaseOutputDir) -and
    @(Get-ChildItem -Force $releaseOutputDir).Count -gt 0) {
    throw "O diretório de saída já contém artefatos. Preserve-o ou remova-o conscientemente antes de repetir a release."
}

New-Item -ItemType Directory -Force -Path $releasePublishDir, $releaseOutputDir | Out-Null

dotnet tool restore
if ($LASTEXITCODE -ne 0) { throw "Falha ao restaurar o vpk." }

dotnet publish (Join-Path $releaseRepoRoot "src/FolhasDaMichelly.Desktop/FolhasDaMichelly.Desktop.csproj") `
    --configuration Release `
    --runtime $releaseRid `
    --self-contained true `
    --no-restore `
    "-p:Version=$Version" `
    --output $releasePublishDir
if ($LASTEXITCODE -ne 0) { throw "Falha ao publicar o aplicativo Windows." }

dotnet tool run vpk -- pack `
    --packId "FolhasDaMichelly" `
    --packVersion $Version `
    --packDir $releasePublishDir `
    --mainExe "FolhasDaMichelly.Desktop.exe" `
    --packTitle "Folhas da Michelly" `
    --runtime $releaseRid `
    --channel $releaseChannel `
    --icon (Join-Path $releaseRepoRoot "src/FolhasDaMichelly.Desktop/Assets/Branding/app-icon.ico") `
    --outputDir $releaseOutputDir
if ($LASTEXITCODE -ne 0) { throw "Falha ao criar o pacote Windows." }

if ($Mode -eq "signed") {
    $installers = @(Get-ChildItem -File $releaseOutputDir -Filter "*-Setup.exe")
    if ($installers.Count -ne 1) {
        throw "Era esperado exatamente um instalador Windows assinado."
    }

    $signature = Get-AuthenticodeSignature $installers[0].FullName
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
        throw "A assinatura Authenticode do instalador não é válida: $($signature.Status)."
    }
}

if ($Mode -eq "validation") {
    @(
        "PACOTE NÃO ASSINADO — SOMENTE VALIDAÇÃO TÉCNICA DA FASE 10."
        "NÃO DISTRIBUIR E NÃO PUBLICAR EM FEED DE ATUALIZAÇÃO."
    ) | Set-Content -Encoding UTF8 (Join-Path $releaseOutputDir "UNSIGNED-VALIDATION-ONLY.txt")
}

$checksumLines = Get-ChildItem -File $releaseOutputDir |
    Where-Object { $_.Name -ne "SHA256SUMS.txt" } |
    Sort-Object Name |
    ForEach-Object {
        $hash = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant()
        "$hash  $($_.Name)"
    }
$checksumLines | Set-Content -Encoding UTF8 (Join-Path $releaseOutputDir "SHA256SUMS.txt")
$checksumLines | ForEach-Object {
    $parts = $_ -split '  ', 2
    $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $releaseOutputDir $parts[1])).Hash.ToLowerInvariant()
    if ($actual -ne $parts[0]) {
        throw "Falha na conferência SHA-256 de $($parts[1])."
    }
}
Write-Host "Pacote $releaseChannel gerado em $releaseOutputDir"
