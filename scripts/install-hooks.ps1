$ErrorActionPreference = 'Stop'
$repoRoot = (& git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or -not $repoRoot) { throw 'Ejecuta esta tarea dentro del repositorio.' }
$configuredHooks = (& git config --local --get core.hooksPath)
if ($configuredHooks -and $configuredHooks.Trim() -ne '.githooks') {
    throw "Ya existe core.hooksPath ($configuredHooks). No se reemplazará automáticamente."
}
$defaultHooks = (& git rev-parse --git-path hooks).Trim()
if (Test-Path -LiteralPath (Join-Path $defaultHooks 'post-commit')) {
    throw 'Ya existe un post-commit en .git/hooks. Revísalo antes de activar .githooks.'
}
& git config --local core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) { throw 'No se pudo configurar core.hooksPath.' }
& git config --local push.followTags true
if ($LASTEXITCODE -ne 0) { throw 'No se pudo activar push.followTags.' }
Write-Host 'Hook habilitado. Cada commit sube el parche de version.txt (salvo que ya cambie la versión) y recibe la etiqueta vX.Y.Z.'
