variable "aws_region" {
  default = "us-east-1"
}

variable "vpc_cidr" {
  default = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "key_name" {
  description = "Name of the key pair"
  type        = string
  default     = "blue-green-key-pair"
}

variable "private_key_base64" {
  description = "Base64 encoded private key for SSH"
  type        = string
}

variable "public_key_path" {
  description = "Path to the public key file"
  type        = string
  default     = "/var/lib/jenkins/workspace/blue-green-deployment-job/blue-green-deployment/blue-green-key.pub"
}

variable "availability_zones" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b"]
}

variable "instance_type" {
  default = "t3.micro"
}

variable "listener_port" {
  type = number
  default = 80
}

variable "blue_target_group_arn" {
  description = "ARN of the Blue target group"
  type        = string
}

variable "green_target_group_arn" {
  description = "ARN of the Green target group"
  type        = string
}

variable "min_size" {
  type    = number
  default = 1
}

variable "max_size" {
  type    = number
  default = 2
}

variable "desired_capacity" {
  type    = number
  default = 1
}

