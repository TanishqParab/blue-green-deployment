output "alb_dns_name" {
  value = aws_lb.main.dns_name
}

output "target_group_arn_blue" {
  value = aws_lb_target_group.blue.arn
}

output "target_group_arn_green" {
  value = aws_lb_target_group.green.arn
}

output "blue_id" {
  description = "Blue target group attachment ID"
  value       = aws_lb_target_group.blue.id
}

output "green_id" {
  description = "Green target group attachment ID"
  value       = aws_lb_target_group.green.id
}
output "alb_arn" {
  description = "ARN of the ALB"
  value       = aws_lb.main.arn
}
