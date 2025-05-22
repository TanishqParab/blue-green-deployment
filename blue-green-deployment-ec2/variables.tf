
variable "aws_region" {
  description = "AWS region to deploy resources"
  type        = string
  default     = "us-east-1"
}

#############################
# VPC Variables
#############################
variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "List of CIDR blocks for public subnets"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "availability_zones" {
  description = "List of availability zones"
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "vpc_name" {
  description = "Name of the VPC"
  type        = string
  default     = "Main VPC"
}

variable "enable_dns_support" {
  description = "Enable DNS support for the VPC"
  type        = bool
  default     = true
}

variable "enable_dns_hostnames" {
  description = "Enable DNS hostnames for the VPC"
  type        = bool
  default     = true
}

variable "subnet_name_prefix" {
  description = "Prefix for subnet names"
  type        = string
  default     = "Public Subnet"
}

variable "igw_name" {
  description = "Name of the Internet Gateway"
  type        = string
  default     = "Main IGW"
}

variable "route_table_name" {
  description = "Name of the route table"
  type        = string
  default     = "Public Route Table"
}

variable "map_public_ip_on_launch" {
  description = "Auto-assign public IP on launch for public subnets"
  type        = bool
  default     = true
}

variable "internet_cidr_block" {
  description = "CIDR block for internet access"
  type        = string
  default     = "0.0.0.0/0"
}

#############################
# Security Group Variables
#############################
variable "security_group_name" {
  description = "Name of the security group"
  type        = string
  default     = "EC2 Security Group"
}

variable "security_group_description" {
  description = "Description of the security group"
  type        = string
  default     = "Security group for EC2 instances"
}

variable "ingress_rules" {
  description = "List of ingress rules for the security group"
  type = list(object({
    from_port   = number
    to_port     = number
    protocol    = string
    cidr_blocks = list(string)
    description = optional(string)
  }))
  default = [
    {
      from_port   = 22
      to_port     = 22
      protocol    = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
      description = "SSH access"
    },
    {
      from_port   = 5000
      to_port     = 5000
      protocol    = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
      description = "Flask application port"
    },
    {
      from_port   = 80
      to_port     = 80
      protocol    = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
      description = "HTTP traffic"
    }
  ]
}

variable "egress_from_port" {
  description = "From port for egress rule"
  type        = number
  default     = 0
}

variable "egress_to_port" {
  description = "To port for egress rule"
  type        = number
  default     = 0
}

variable "egress_protocol" {
  description = "Protocol for egress rule"
  type        = string
  default     = "-1"
}

variable "egress_cidr_blocks" {
  description = "CIDR blocks for egress rule"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "egress_description" {
  description = "Description for the egress rule"
  type        = string
  default     = "Allow all outbound traffic"
}



variable "sg_additional_tags" {
  description = "Additional tags for the security group"
  type        = map(string)
  default     = {}
}

#############################
# ALB Variables
#############################
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

#############################
# EC2 Variables
#############################
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

variable "instance_type" {
  description = "EC2 instance type"
  type        = string
  default     = "t3.micro"
}

variable "ami_id" {
  description = "Amazon Machine Image (AMI) ID"
  type        = string
  default     = "ami-05b10e08d247fb927"
}

variable "blue_instance_name" {
  description = "Name tag for the blue instance"
  type        = string
  default     = "Blue-Instance"
}

variable "green_instance_name" {
  description = "Name tag for the green instance"
  type        = string
  default     = "Green-Instance"
}

variable "environment_tag" {
  description = "Environment tag for EC2 instances"
  type        = string
  default     = "Blue-Green"
}

variable "ssh_user" {
  description = "SSH user for connecting to instances"
  type        = string
  default     = "ec2-user"
}

variable "ec2_additional_tags" {
  description = "Additional tags for EC2 instances"
  type        = map(string)
  default     = {}
}

#############################
# ASG Variables
#############################
variable "asg_name" {
  description = "Name for the Auto Scaling Group"
  type        = string
  default     = "blue_green_asg"
}

variable "min_size" {
  description = "Minimum size of the Auto Scaling Group"
  type        = number
  default     = 1
}

variable "max_size" {
  description = "Maximum size of the Auto Scaling Group"
  type        = number
  default     = 2
}

variable "desired_capacity" {
  description = "Desired capacity of the Auto Scaling Group"
  type        = number
  default     = 1
}

variable "launch_template_name_prefix" {
  description = "Name prefix for the launch template"
  type        = string
  default     = "blue-green-launch-template"
}

variable "associate_public_ip_address" {
  description = "Whether to associate a public IP address with instances"
  type        = bool
  default     = true
}

variable "health_check_type" {
  description = "Type of health check to perform (EC2 or ELB)"
  type        = string
  default     = "ELB"
}

variable "health_check_grace_period" {
  description = "Time (in seconds) after instance comes into service before checking health"
  type        = number
  default     = 300
}

variable "min_healthy_percentage" {
  description = "Minimum percentage of healthy instances during instance refresh"
  type        = number
  default     = 50
}

variable "instance_warmup" {
  description = "Time (in seconds) for new instances to warm up during instance refresh"
  type        = number
  default     = 60
}

variable "user_data_script" {
  description = "User data script to run on instance launch"
  type        = string
  default     = null
}
