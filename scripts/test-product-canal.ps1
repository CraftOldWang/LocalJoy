param([string]$BaseUrl = 'http://127.0.0.1:8081')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not ([Uri]$BaseUrl).IsLoopback) { throw 'This smoke test is intended for a local development server only.' }
$marker = 'canal-smoke-' + [Guid]::NewGuid().ToString('N')
$created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/product" -ContentType 'application/json' -Body (@{
    shopId=1; title=$marker; price=100; status=1
} | ConvertTo-Json)
if (-not $created.success -or [string]$created.data -notmatch '^\d+$') { throw 'Could not create the smoke-test product' }
$productId = [long]$created.data
$cacheKey = 'cache:product:' + $productId
try {
    # Let the insert event drain before warming; this test targets a subsequent direct SQL update.
    Start-Sleep -Seconds 2
    $before = Invoke-RestMethod "$BaseUrl/product/$productId"
    $exists = docker exec hmdp-redis redis-cli EXISTS $cacheKey
    if (-not $before.success -or $before.data.title -ne $marker -or $exists -ne '1') { throw 'Initial cache was not populated' }
    $newTitle = $marker + '-updated'
    $timer = [Diagnostics.Stopwatch]::StartNew()
    docker exec hmdp-mysql mysql -uroot -p123456 -e "UPDATE hmdp.tb_product SET title='$newTitle' WHERE id=$productId AND title='$marker';"
    if ($LASTEXITCODE -ne 0) { throw 'Direct SQL update failed' }
    do {
        $exists = docker exec hmdp-redis redis-cli EXISTS $cacheKey
        if ($exists -eq '0') { break }
        Start-Sleep -Milliseconds 150
    } while ($timer.Elapsed.TotalSeconds -lt 15)
    $observedMs = $timer.ElapsedMilliseconds
    if ($exists -ne '0') { throw 'Canal did not invalidate the product cache within 15 seconds' }
    $after = Invoke-RestMethod "$BaseUrl/product/$productId"
    if (-not $after.success -or $after.data.title -ne $newTitle) { throw 'Product cache did not reload the updated database value' }
    $report = [ordered]@{
        timestamp=(Get-Date).ToString('o'); productId=$productId
        operation='direct MySQL UPDATE -> Canal -> RocketMQ -> cache invalidation -> HTTP reload'
        cachePresentBeforeUpdate=$true; invalidationObserved=$true; updatedValueObserved=$true
        observationMsIncludingDockerCommands=$observedMs
        note='Single local smoke test; includes Docker CLI and polling overhead, not a latency benchmark.'
    }
    New-Item -ItemType Directory -Force target | Out-Null
    $report | ConvertTo-Json | Set-Content target/resume-canal-evidence.json -Encoding utf8
    $report | ConvertTo-Json
} finally {
    # Delete only the synthetic row just created by this invocation.
    docker exec hmdp-mysql mysql -uroot -p123456 -e "DELETE FROM hmdp.tb_product WHERE id=$productId AND title IN ('$marker','$marker-updated');"
    docker exec hmdp-redis redis-cli DEL $cacheKey | Out-Null
}
