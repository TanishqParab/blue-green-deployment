output "security_group_id" {
  description = "ID of the EC2 security group"
  value       = aws_security_group.ec2_sg.id
}

output "security_group_arn" {
  description = "ARN of the EC2 security group"
  value       = aws_security_group.ec2_sg.arn
}

output "security_group_name" {
  description = "Name of the EC2 security group"
  value       = aws_security_group.ec2_sg.name
}

output "security_group_vpc_id" {
  description = "VPC ID of the EC2 security group"
  value       = aws_security_group.ec2_sg.vpc_id
}
