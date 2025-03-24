variable "key_name" {
  description = "The name of the key pair to use for the instance"
  type        = string
}

variable "subnet_ids" {
  description = "The list of subnet IDs to launch resources in"
  type        = list(string)
}

/*
variable "alb_target_group_arn" {
  description = "The ARN of the ALB target group"
  type        = string
}*/

variable "security_group_id" {
  description = "The ID of the security group"
  type        = string
}

variable "blue_target_group_arn" {
  description = "The ARN of the Blue Target Group"
  type        = string
}

variable "green_target_group_arn" {
  description = "The ARN of the Green Target Group"
  type        = string
}

