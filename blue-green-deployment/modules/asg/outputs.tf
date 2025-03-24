output "asg_id" {
  value = aws_autoscaling_group.blue_green_asg
}

output "asg_name" {
  description = "The name of the Auto Scaling Group"
  value       = aws_autoscaling_group.blue_green_asg.name
}

output "blue_target_group_arn" {
  description = "Target group ARN for Blue instances"
  value       = var.blue_target_group_arn
}

output "green_target_group_arn" {
  description = "Target group ARN for Green instances"
  value       = var.green_target_group_arn
}
