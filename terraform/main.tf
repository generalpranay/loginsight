terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket = "loginsight-tfstate"
    key    = "loginsight/terraform.tfstate"
    region = "us-east-1"
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "loginsight"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# ─────────────────────────────────────────────
# Variables
# ─────────────────────────────────────────────

variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "AWS region for all resources"
}

variable "environment" {
  type        = string
  default     = "production"
  description = "Deployment environment label"
}

variable "kafka_broker_instance_type" {
  type        = string
  default     = "kafka.m5.large"
  description = "MSK broker node instance type"
}

variable "kafka_broker_storage_gb" {
  type        = number
  default     = 500
  description = "EBS volume size per MSK broker in GiB"
}

variable "vpc_id" {
  type        = string
  description = "Existing VPC to deploy MSK into"
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Three private subnets (one per AZ) for MSK brokers"
}

# ─────────────────────────────────────────────
# Security Group — MSK
# ─────────────────────────────────────────────

resource "aws_security_group" "msk" {
  name        = "loginsight-msk"
  description = "Allow Kafka traffic from within the VPC"
  vpc_id      = var.vpc_id

  ingress {
    description = "Kafka TLS"
    from_port   = 9094
    to_port     = 9094
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  ingress {
    description = "Kafka plaintext (internal only)"
    from_port   = 9092
    to_port     = 9092
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  ingress {
    description = "Zookeeper"
    from_port   = 2181
    to_port     = 2181
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

data "aws_vpc" "selected" {
  id = var.vpc_id
}

# ─────────────────────────────────────────────
# MSK Cluster Configuration
# ─────────────────────────────────────────────

resource "aws_msk_configuration" "loginsight" {
  name              = "loginsight-kafka-config"
  kafka_versions    = ["3.5.1"]
  server_properties = <<-PROPS
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=6
    log.retention.hours=168
    log.segment.bytes=1073741824
    # Required for Exactly-Once Semantics
    transaction.state.log.replication.factor=3
    transaction.state.log.min.isr=2
    enable.idempotence=true
    # Sticky assignor support
    group.initial.rebalance.delay.ms=3000
  PROPS
}

# ─────────────────────────────────────────────
# MSK Cluster — 3 brokers across 3 AZs
# ─────────────────────────────────────────────

resource "aws_msk_cluster" "loginsight" {
  cluster_name           = "loginsight-kafka"
  kafka_version          = "3.5.1"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = var.kafka_broker_instance_type
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.kafka_broker_storage_gb

        # Automatically scale storage up to 2 TiB; never scales down
        provisioned_throughput {
          enabled           = true
          volume_throughput = 250
        }
      }
    }

    connectivity_info {
      public_access {
        type = "DISABLED"
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.loginsight.arn
    revision = aws_msk_configuration.loginsight.latest_revision
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  client_authentication {
    sasl {
      iam = true
    }
  }

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/loginsight/msk"
  retention_in_days = 14
}

# ─────────────────────────────────────────────
# S3 — Cold log archival
# ─────────────────────────────────────────────

resource "aws_s3_bucket" "cold_logs" {
  bucket        = "loginsight-cold-logs-${data.aws_caller_identity.current.account_id}"
  force_destroy = false
}

resource "aws_s3_bucket_versioning" "cold_logs" {
  bucket = aws_s3_bucket.cold_logs.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "cold_logs" {
  bucket = aws_s3_bucket.cold_logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "cold_logs" {
  bucket = aws_s3_bucket.cold_logs.id

  rule {
    id     = "glacier-after-90-days"
    status = "Enabled"

    transition {
      days          = 90
      storage_class = "GLACIER"
    }

    expiration {
      days = 2555  # 7 years retention for compliance
    }
  }
}

resource "aws_s3_bucket_public_access_block" "cold_logs" {
  bucket                  = aws_s3_bucket.cold_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_caller_identity" "current" {}

# ─────────────────────────────────────────────
# Outputs
# ─────────────────────────────────────────────

output "msk_bootstrap_brokers_tls" {
  description = "TLS bootstrap broker string for Kafka clients"
  value       = aws_msk_cluster.loginsight.bootstrap_brokers_sasl_iam
  sensitive   = false
}

output "cold_storage_bucket" {
  description = "S3 bucket name for cold log archival"
  value       = aws_s3_bucket.cold_logs.bucket
}

output "msk_cluster_arn" {
  description = "ARN of the MSK cluster"
  value       = aws_msk_cluster.loginsight.arn
}
