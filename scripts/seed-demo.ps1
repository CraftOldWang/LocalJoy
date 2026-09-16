param([string]$BaseUrl = 'http://127.0.0.1:8081', [long]$ShopId = 1)
$ErrorActionPreference = 'Stop'
if (-not ([uri]$BaseUrl).IsLoopback) { throw 'Demo seeding is restricted to a local backend.' }
if ($ShopId -le 0) { throw 'ShopId must be positive.' }
$existing = Invoke-RestMethod "$BaseUrl/product/list/$ShopId"
if (-not $existing.success) { throw $existing.errorMsg }
$chinaNow = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow, 'China Standard Time')
$offers = @(
    @{ title = 'LocalJoy 演示 · 港式下午茶'; subTitle = '一杯奶茶，一段慢下来的时光'; price = 1990; stock = 30; image = '/imgs/afternoon-tea-generated.png'; description = '本地演示套餐：港式奶茶与当日小点。用于体验商品秒杀、订单查询和模拟支付，不可用于实际到店消费。' },
    @{ title = 'LocalJoy 演示 · 双人轻食套餐'; subTitle = '和朋友一起，吃好这一餐'; price = 5990; stock = 20; image = '/imgs/lunch-generated.png'; description = '本地演示套餐：双人轻食组合。下单后请在期限内模拟支付，超时订单会自动关闭。本演示不会发生真实扣款。' }
)
$ids = @()
foreach ($offer in $offers) {
    $same = @($existing.data | Where-Object { $_.title -eq $offer.title -and $_.endTime -and [datetime]$_.endTime -gt $chinaNow })
    if ($same.Count -gt 0) { $id = $same[0].id }
    else {
        $body = @{} + $offer
        $body.shopId = $ShopId
        $body.status = 1
        $body.beginTime = $chinaNow.AddMinutes(-5).ToString('yyyy-MM-ddTHH:mm:ss')
        $body.endTime = $chinaNow.AddDays(7).ToString('yyyy-MM-ddTHH:mm:ss')
        $json = $body | ConvertTo-Json
        $result = Invoke-RestMethod "$BaseUrl/product/seckill" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json))
        if (-not $result.success) { throw $result.errorMsg }
        $id = $result.data
    }
    $ids += [pscustomobject]@{ id = $id; title = $offer.title; page = "http://127.0.0.1:8088/products/$id" }
}
$ids | ConvertTo-Json
# Existing active demo offers are reused; their inventory and orders are never reset.
