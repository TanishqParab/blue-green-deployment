output "alb_dns_name" {
  description = "The DNS name of the ALB"
  value       = aws_lb.main.dns_name
}


output "blue_target_group_arn" {
  description = "The ARN of the blue target group"
  value       = aws_lb_target_group.blue.arn
}

output "green_target_group_arn" {
  description = "The ARN of the green target group"
  value       = aws_lb_target_group.green.arn
}

output "alb_arn" {
  description = "The ARN of the ALB"
  value       = aws_lb.main.arn
}

output "alb_id" {
  description = "The ID of the ALB"
  value       = aws_lb.main.id
}

output "http_listener_arn" {
  description = "The ARN of the HTTP listener"
  value       = aws_lb_listener.http.arn
}

output "target_groups" {
  description = "Map of target groups created and their attributes"
  value = {
    blue = {
      arn  = aws_lb_target_group.blue.arn
      name = aws_lb_target_group.blue.name
      id   = aws_lb_target_group.blue.id
    }
    green = {
      arn  = aws_lb_target_group.green.arn
      name = aws_lb_target_group.green.name
      id   = aws_lb_target_group.green.id
    }
  }
}
