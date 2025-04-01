### Updated Root main.tf (Fixed Errors)

provider "aws" {
  region = var.aws_region
}

module "vpc" {
  source              = "./modules/vpc"
  vpc_cidr            = var.vpc_cidr
  public_subnet_cidrs = var.public_subnet_cidrs
  availability_zones  = var.availability_zones
}

module "security_group" {
  source = "./modules/security_group"
  vpc_id = module.vpc.vpc_id
}

module "alb" {
  source            = "./modules/alb"
  vpc_id            = module.vpc.vpc_id
  subnet_ids        = module.vpc.public_subnet_ids # 🔥 Fix: Use correct attribute
  security_group_id = module.security_group.security_group_id
  listener_port = var.listener_port
  health_check_path = var.health_check_path
  health_check_interval = var.health_check_interval
  health_check_timeout = var.health_check_timeout
  healthy_threshold   = var.healthy_threshold
  unhealthy_threshold = var.unhealthy_threshold
}



module "ec2" {
  source                = "./modules/ec2"
  subnet_id             = module.vpc.public_subnet_ids[0] # Select the first subnet
  ec2_security_group_id = module.security_group.security_group_id
  key_name              = var.key_name
  private_key_base64    = var.private_key_base64
  public_key_path = var.public_key_path
  instance_type         = var.instance_type
}

module "asg" {
  source               = "./modules/asg"
  subnet_ids           = module.vpc.public_subnet_ids # Ensure this output exists
  security_group_id    = module.security_group.security_group_id
  key_name             = var.key_name
  #alb_target_group_arn = var.alb_target_group_arn
  alb_target_group_arns = [ module.alb.blue_target_group_arn, module.alb.green_target_group_arn ]
  desired_capacity =  var.desired_capacity
  min_size = var.min_size
  max_size = var.max_size
}





### ALB Module variables.tf


