Set-Location C:\project\loginsight

# Load secrets from .env.ps1 if present (copy .env.ps1.example → .env.ps1)
if (Test-Path ".\.env.ps1") { . ".\.env.ps1" }

if (-not $env:INFLUXDB_TOKEN) {
    Write-Error "INFLUXDB_TOKEN is not set. Copy .env.ps1.example to .env.ps1 and fill in the token."
    exit 1
}

$env:KAFKA_BOOTSTRAP_SERVERS = if ($env:KAFKA_BOOTSTRAP_SERVERS) { $env:KAFKA_BOOTSTRAP_SERVERS } else { 'localhost:9092' }
$env:KAFKA_TOPIC              = if ($env:KAFKA_TOPIC) { $env:KAFKA_TOPIC } else { 'raw-logs' }
$env:KAFKA_GROUP_ID           = if ($env:KAFKA_GROUP_ID) { $env:KAFKA_GROUP_ID } else { 'loginsight-ingestion' }
$env:ELASTICSEARCH_URL        = if ($env:ELASTICSEARCH_URL) { $env:ELASTICSEARCH_URL } else { 'http://localhost:9200' }
$env:INFLUXDB_URL             = if ($env:INFLUXDB_URL) { $env:INFLUXDB_URL } else { 'http://localhost:8086' }
$env:INFLUXDB_ORG             = if ($env:INFLUXDB_ORG) { $env:INFLUXDB_ORG } else { 'loginsight' }
$env:INFLUXDB_BUCKET          = if ($env:INFLUXDB_BUCKET) { $env:INFLUXDB_BUCKET } else { 'metrics' }

java -jar ingestion\target\ingestion-1.0.0-SNAPSHOT.jar
