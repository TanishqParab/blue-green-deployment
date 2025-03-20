variable "vpc_id" {
  description = "VPC ID where the ALB will be created"
  type        = string
}

variable "subnet_ids" {
  description = "List of subnet IDs for the ALB"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security Group ID for the ALB"
  type        = string
}

variable "listener_port" {
  description = "Listener port for the ALB"
  type        = number
  default     = 5000
}

variable "blue_instance_id" {
  description = "ID of the Blue EC2 instance"
  type        = string
}

variable "green_instance_id" {
  description = "ID of the Green EC2 instance"
  type        = string
}
