Set-Location C:\project\loginsight
$env:ELASTICSEARCH_URL                  = 'http://localhost:9200'
$env:INFLUXDB_URL                       = 'http://localhost:8086'
$env:INFLUXDB_TOKEN                     = 'loginsight-influxdb-token'
$env:INFLUXDB_ORG                       = 'loginsight'
$env:INFLUXDB_BUCKET                    = 'metrics'
$env:LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
mvn spring-boot:run -pl api "-Dspring-boot.run.profiles=local"
