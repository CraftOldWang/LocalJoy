param([string]$JavaHome = $env:JAVA_HOME, [switch]$RealMq, [switch]$Benchmark)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
# Dedicated schema. Never import the course SQL over the live hmdp database.
docker exec hmdp-mysql mysql -uroot -p123456 -e 'CREATE DATABASE IF NOT EXISTS hmdp_resume_test CHARACTER SET utf8mb4; CREATE TABLE IF NOT EXISTS hmdp_resume_test.tb_voucher_order (id bigint PRIMARY KEY, update_time timestamp NULL);'
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare isolated test schema' }
$mavenArgs = @('-Dtest=ResumeEvidenceTest')
if ($RealMq) { $mavenArgs += '-Dresume.realMq=true' }
if ($Benchmark) { $mavenArgs += '-Dresume.benchmark=true' }
mvn @mavenArgs test
if ($LASTEXITCODE -ne 0) { throw 'Resume evidence tests failed' }
