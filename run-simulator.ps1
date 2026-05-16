$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:KAFKA_TOPIC = 'raw-logs'
Set-Location C:\project\loginsight
java -cp ingestion\target\ingestion-1.0.0-SNAPSHOT.jar com.loginsight.ingestion.LogSimulator
