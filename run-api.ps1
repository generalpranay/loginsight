Set-Location C:\project\loginsight

# Load secrets from .env.ps1 if present (copy .env.ps1.example → .env.ps1)
if (Test-Path ".\.env.ps1") { . ".\.env.ps1" }

if (-not $env:INFLUXDB_TOKEN) {
    Write-Error "INFLUXDB_TOKEN is not set. Copy .env.ps1.example to .env.ps1 and fill in the token."
    exit 1
}

$env:ELASTICSEARCH_URL                  = if ($env:ELASTICSEARCH_URL) { $env:ELASTICSEARCH_URL } else { 'http://localhost:9200' }
$env:INFLUXDB_URL                       = if ($env:INFLUXDB_URL) { $env:INFLUXDB_URL } else { 'http://localhost:8086' }
$env:INFLUXDB_ORG                       = if ($env:INFLUXDB_ORG) { $env:INFLUXDB_ORG } else { 'loginsight' }
$env:INFLUXDB_BUCKET                    = if ($env:INFLUXDB_BUCKET) { $env:INFLUXDB_BUCKET } else { 'metrics' }
$env:LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS = if ($env:LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS) { $env:LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS } else { 'localhost:9092' }

mvn spring-boot:run -pl api "-Dspring-boot.run.profiles=local"
