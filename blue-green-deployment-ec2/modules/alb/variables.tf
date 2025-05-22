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

variable "alb_name" {
  description = "Name of the Application Load Balancer"
  type        = string
  default     = "blue-green-alb"
}

variable "internal" {
  description = "Whether the ALB is internal or internet-facing"
  type        = bool
  default     = false
}

variable "load_balancer_type" {
  description = "Type of load balancer"
  type        = string
  default     = "application"
}

variable "blue_target_group_name" {
  description = "Name of the blue target group"
  type        = string
  default     = "blue-tg"
}

variable "green_target_group_name" {
  description = "Name of the green target group"
  type        = string
  default     = "green-tg"
}

variable "target_group_port" {
  description = "Port for the target groups"
  type        = number
  default     = 5000
}

variable "target_group_protocol" {
  description = "Protocol for the target groups"
  type        = string
  default     = "HTTP"
}

variable "target_type" {
  description = "Type of target for the target groups"
  type        = string
  default     = "instance"
}

variable "listener_port" {
  description = "Port for the ALB listener"
  type        = number
  default     = 80
}

variable "listener_protocol" {
  description = "Protocol for the ALB listener"
  type        = string
  default     = "HTTP"
}

variable "health_check_path" {
  description = "The health check path for the target groups"
  type        = string
  default     = "/health"
}

variable "health_check_interval" {
  description = "The interval (in seconds) between health checks"
  type        = number
  default     = 30
}

variable "health_check_timeout" {
  description = "The timeout (in seconds) for each health check"
  type        = number
  default     = 5
}

variable "healthy_threshold" {
  description = "The number of successful health checks required to mark a target as healthy"
  type        = number
  default     = 3
}

variable "unhealthy_threshold" {
  description = "The number of failed health checks required to mark a target as unhealthy"
  type        = number
  default     = 2
}

variable "health_check_matcher" {
  description = "HTTP codes to use when checking for a successful response from a target"
  type        = string
  default     = "200"
}

variable "blue_weight" {
  description = "Initial weight for the blue target group"
  type        = number
  default     = 100
}

variable "green_weight" {
  description = "Initial weight for the green target group"
  type        = number
  default     = 0
}

variable "stickiness_enabled" {
  description = "Whether stickiness is enabled for the load balancer"
  type        = bool
  default     = true
}

variable "stickiness_duration" {
  description = "Duration (in seconds) for stickiness"
  type        = number
  default     = 300
}
