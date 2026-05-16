$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:KAFKA_TOPIC = 'raw-logs'
$env:KAFKA_GROUP_ID = 'loginsight-ingestion'
$env:ELASTICSEARCH_URL = 'http://localhost:9200'
$env:INFLUXDB_URL = 'http://localhost:8086'
$env:INFLUXDB_TOKEN = 'loginsight-influxdb-token'
$env:INFLUXDB_ORG = 'loginsight'
$env:INFLUXDB_BUCKET = 'metrics'
Set-Location C:\project\loginsight
java -jar ingestion\target\ingestion-1.0.0-SNAPSHOT.jar
