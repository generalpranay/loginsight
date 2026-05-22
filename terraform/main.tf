terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
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

variable "vpc_id" {
  type        = string
  description = "Existing VPC to deploy all resources into"
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Three private subnets (one per AZ) — used by MSK, EKS, and OpenSearch"
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

variable "eks_version" {
  type        = string
  default     = "1.30"
  description = "Kubernetes version for the EKS cluster"
}

variable "eks_node_instance_type" {
  type        = string
  default     = "t3.xlarge"
  description = "EC2 instance type for EKS managed node group workers"
}

variable "eks_node_min_size" {
  type        = number
  default     = 3
  description = "Minimum number of EKS worker nodes"
}

variable "eks_node_max_size" {
  type        = number
  default     = 12
  description = "Maximum number of EKS worker nodes (matches ingestion HPA max)"
}

variable "eks_node_desired_size" {
  type        = number
  default     = 3
  description = "Initial number of EKS worker nodes"
}

variable "opensearch_instance_type" {
  type        = string
  default     = "r6g.large.search"
  description = "OpenSearch data node instance type"
}

variable "opensearch_instance_count" {
  type        = number
  default     = 3
  description = "Number of OpenSearch data nodes (must be a multiple of AZ count)"
}

variable "opensearch_volume_gb" {
  type        = number
  default     = 100
  description = "EBS gp3 volume size per OpenSearch node in GiB"
}

# ─────────────────────────────────────────────
# Data sources
# ─────────────────────────────────────────────

data "aws_vpc" "selected" {
  id = var.vpc_id
}

data "aws_caller_identity" "current" {}

data "tls_certificate" "eks" {
  url = aws_eks_cluster.loginsight.identity[0].oidc[0].issuer
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

# ─────────────────────────────────────────────
# MSK Cluster Configuration
# ─────────────────────────────────────────────

resource "aws_msk_configuration" "loginsight" {
  name           = "loginsight-kafka-config"
  kafka_versions = ["3.5.1"]
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

        # Automatically scales storage up; never scales down
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
      days = 2555 # 7-year retention for compliance
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

# ─────────────────────────────────────────────
# EKS — cluster IAM role
# ─────────────────────────────────────────────

resource "aws_iam_role" "eks_cluster" {
  name = "loginsight-eks-cluster"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "eks_cluster_policy" {
  role       = aws_iam_role.eks_cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

# ─────────────────────────────────────────────
# EKS — cluster (private endpoint only)
# ─────────────────────────────────────────────

resource "aws_security_group" "eks_cluster" {
  name        = "loginsight-eks-cluster"
  description = "EKS control plane security group"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_eks_cluster" "loginsight" {
  name     = "loginsight"
  version  = var.eks_version
  role_arn = aws_iam_role.eks_cluster.arn

  vpc_config {
    subnet_ids              = var.private_subnet_ids
    security_group_ids      = [aws_security_group.eks_cluster.id]
    endpoint_private_access = true
    endpoint_public_access  = false
  }

  enabled_cluster_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  depends_on = [aws_iam_role_policy_attachment.eks_cluster_policy]
}

resource "aws_cloudwatch_log_group" "eks" {
  name              = "/aws/eks/loginsight/cluster"
  retention_in_days = 14
}

# ─────────────────────────────────────────────
# EKS — node group IAM role
# ─────────────────────────────────────────────

resource "aws_iam_role" "eks_nodes" {
  name = "loginsight-eks-nodes"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "eks_worker_node_policy" {
  role       = aws_iam_role.eks_nodes.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
}

resource "aws_iam_role_policy_attachment" "eks_cni_policy" {
  role       = aws_iam_role.eks_nodes.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}

resource "aws_iam_role_policy_attachment" "eks_ecr_readonly" {
  role       = aws_iam_role.eks_nodes.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

# ─────────────────────────────────────────────
# EKS — managed node group
# ─────────────────────────────────────────────

resource "aws_eks_node_group" "loginsight" {
  cluster_name    = aws_eks_cluster.loginsight.name
  node_group_name = "loginsight-workers"
  node_role_arn   = aws_iam_role.eks_nodes.arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = [var.eks_node_instance_type]
  ami_type        = "AL2023_x86_64_STANDARD"

  scaling_config {
    min_size     = var.eks_node_min_size
    max_size     = var.eks_node_max_size
    desired_size = var.eks_node_desired_size
  }

  update_config {
    max_unavailable = 1
  }

  labels = {
    role = "worker"
  }

  depends_on = [
    aws_iam_role_policy_attachment.eks_worker_node_policy,
    aws_iam_role_policy_attachment.eks_cni_policy,
    aws_iam_role_policy_attachment.eks_ecr_readonly,
  ]
}

# ─────────────────────────────────────────────
# OIDC provider — enables IRSA for all pods
# ─────────────────────────────────────────────

resource "aws_iam_openid_connect_provider" "eks" {
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.eks.certificates[0].sha1_fingerprint]
  url             = aws_eks_cluster.loginsight.identity[0].oidc[0].issuer
}

locals {
  oidc_issuer_host = replace(aws_iam_openid_connect_provider.eks.url, "https://", "")
}

# ─────────────────────────────────────────────
# EBS CSI add-on — persistent volumes for InfluxDB
#
# InfluxDB has no managed AWS equivalent. It runs as a StatefulSet
# in-cluster via its Helm chart. The EBS CSI driver backs its PVCs
# with gp3 volumes so data survives pod restarts.
# ─────────────────────────────────────────────

resource "aws_iam_role" "ebs_csi" {
  name = "loginsight-ebs-csi"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.eks.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_issuer_host}:sub" = "system:serviceaccount:kube-system:ebs-csi-controller-sa"
          "${local.oidc_issuer_host}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.ebs_csi.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

resource "aws_eks_addon" "ebs_csi" {
  cluster_name             = aws_eks_cluster.loginsight.name
  addon_name               = "aws-ebs-csi-driver"
  service_account_role_arn = aws_iam_role.ebs_csi.arn

  depends_on = [aws_eks_node_group.loginsight]
}

# ─────────────────────────────────────────────
# IRSA — workload role (ingestion + API pods)
#
# Assumed by the Kubernetes service account annotated with
# eks.amazonaws.com/role-arn in helm/loginsight/values.yaml.
# Grants: MSK IAM auth, S3 cold-log writes, OpenSearch read/write.
# ─────────────────────────────────────────────

resource "aws_iam_role" "loginsight_workload" {
  name = "loginsight-workload"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.eks.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_issuer_host}:sub" = [
            "system:serviceaccount:default:loginsight",
            "system:serviceaccount:default:loginsight-ingestion",
          ]
          "${local.oidc_issuer_host}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
}

resource "aws_iam_policy" "loginsight_workload" {
  name        = "loginsight-workload"
  description = "MSK IAM auth, S3 cold-log writes, OpenSearch read/write for loginsight pods"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "MSKClusterAccess"
        Effect = "Allow"
        Action = [
          "kafka-cluster:Connect",
          "kafka-cluster:AlterCluster",
          "kafka-cluster:DescribeCluster",
          "kafka-cluster:DescribeClusterDynamicConfiguration",
        ]
        Resource = aws_msk_cluster.loginsight.arn
      },
      {
        Sid    = "MSKTopicAccess"
        Effect = "Allow"
        Action = [
          "kafka-cluster:CreateTopic",
          "kafka-cluster:DescribeTopic",
          "kafka-cluster:AlterTopic",
          "kafka-cluster:WriteData",
          "kafka-cluster:ReadData",
        ]
        Resource = "arn:aws:kafka:${var.aws_region}:${data.aws_caller_identity.current.account_id}:topic/loginsight-kafka/*"
      },
      {
        Sid    = "MSKGroupAccess"
        Effect = "Allow"
        Action = [
          "kafka-cluster:AlterGroup",
          "kafka-cluster:DescribeGroup",
        ]
        Resource = "arn:aws:kafka:${var.aws_region}:${data.aws_caller_identity.current.account_id}:group/loginsight-kafka/*"
      },
      {
        Sid    = "S3ColdLogs"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:ListBucket",
          "s3:DeleteObject",
        ]
        Resource = [
          aws_s3_bucket.cold_logs.arn,
          "${aws_s3_bucket.cold_logs.arn}/*",
        ]
      },
      {
        Sid    = "OpenSearchAccess"
        Effect = "Allow"
        Action = [
          "es:ESHttpGet",
          "es:ESHttpPost",
          "es:ESHttpPut",
          "es:ESHttpDelete",
          "es:ESHttpHead",
          "es:ESHttpPatch",
        ]
        Resource = "${aws_opensearch_domain.loginsight.arn}/*"
      },
    ]
  })
}

resource "aws_iam_role_policy_attachment" "loginsight_workload" {
  role       = aws_iam_role.loginsight_workload.name
  policy_arn = aws_iam_policy.loginsight_workload.arn
}

# ─────────────────────────────────────────────
# OpenSearch — security group
# ─────────────────────────────────────────────

resource "aws_security_group" "opensearch" {
  name        = "loginsight-opensearch"
  description = "Allow HTTPS to OpenSearch from within the VPC"
  vpc_id      = var.vpc_id

  ingress {
    description = "OpenSearch HTTPS"
    from_port   = 443
    to_port     = 443
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

# ─────────────────────────────────────────────
# OpenSearch — domain (managed Elasticsearch replacement)
#
# Fine-grained access control is enabled with the workload IAM role
# as the master user. Pods authenticate via SigV4-signed requests
# (the AWS SDK handles signing automatically when running under IRSA).
# ─────────────────────────────────────────────

resource "aws_opensearch_domain" "loginsight" {
  domain_name    = "loginsight"
  engine_version = "OpenSearch_2.11"

  cluster_config {
    instance_type          = var.opensearch_instance_type
    instance_count         = var.opensearch_instance_count
    zone_awareness_enabled = true

    zone_awareness_config {
      availability_zone_count = 3
    }
  }

  vpc_options {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [aws_security_group.opensearch.id]
  }

  ebs_options {
    ebs_enabled = true
    volume_size = var.opensearch_volume_gb
    volume_type = "gp3"
    throughput  = 250
  }

  encrypt_at_rest {
    enabled = true
  }

  node_to_node_encryption {
    enabled = true
  }

  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  advanced_security_options {
    enabled                        = true
    anonymous_auth_enabled         = false
    internal_user_database_enabled = false

    master_user_options {
      master_user_arn = aws_iam_role.loginsight_workload.arn
    }
  }

  log_publishing_options {
    cloudwatch_log_group_arn = aws_cloudwatch_log_group.opensearch.arn
    log_type                 = "INDEX_SLOW_LOGS"
  }

  log_publishing_options {
    cloudwatch_log_group_arn = aws_cloudwatch_log_group.opensearch.arn
    log_type                 = "SEARCH_SLOW_LOGS"
  }
}

resource "aws_cloudwatch_log_group" "opensearch" {
  name              = "/loginsight/opensearch"
  retention_in_days = 14
}

resource "aws_opensearch_domain_policy" "loginsight" {
  domain_name = aws_opensearch_domain.loginsight.domain_name

  access_policies = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { AWS = aws_iam_role.loginsight_workload.arn }
      Action    = "es:*"
      Resource  = "${aws_opensearch_domain.loginsight.arn}/*"
    }]
  })
}

# ─────────────────────────────────────────────
# Outputs
# ─────────────────────────────────────────────

output "msk_bootstrap_brokers_tls" {
  description = "IAM/TLS bootstrap broker string — set as LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS"
  value       = aws_msk_cluster.loginsight.bootstrap_brokers_sasl_iam
}

output "msk_cluster_arn" {
  description = "ARN of the MSK cluster"
  value       = aws_msk_cluster.loginsight.arn
}

output "cold_storage_bucket" {
  description = "S3 bucket name for cold log archival"
  value       = aws_s3_bucket.cold_logs.bucket
}

output "eks_cluster_name" {
  description = "EKS cluster name — pass to aws eks update-kubeconfig"
  value       = aws_eks_cluster.loginsight.name
}

output "eks_cluster_endpoint" {
  description = "EKS API server endpoint (private — requires VPN or bastion)"
  value       = aws_eks_cluster.loginsight.endpoint
}

output "eks_cluster_certificate_authority" {
  description = "Base64-encoded CA data for the EKS cluster"
  value       = aws_eks_cluster.loginsight.certificate_authority[0].data
  sensitive   = true
}

output "opensearch_endpoint" {
  description = "OpenSearch HTTPS endpoint — set as ELASTICSEARCH_URL"
  value       = "https://${aws_opensearch_domain.loginsight.endpoint}"
}

output "workload_iam_role_arn" {
  description = "IRSA role ARN — paste into helm/loginsight/values.yaml serviceAccount.annotations"
  value       = aws_iam_role.loginsight_workload.arn
}
