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
  blue_instance_id = module.ec2.blue_instance_id
  green_instance_id = module.ec2.green_instance_id
}



module "ec2" {
  source                = "./modules/ec2"
  subnet_id             = module.vpc.public_subnet_ids # Select the first subnet
  ec2_security_group_id = module.security_group.security_group_id
  key_name              = var.key_name
  private_key_base64    = var.private_key_base64
  public_key_path = var.public_key_path
  instance_type         = var.instance_type
}

module "asg" {
  source               = "./modules/asg"
  alb_target_group_arn = module.alb.target_group_arn_blue
  subnet_ids           = module.vpc.public_subnet_ids # ✅ Make sure this exists!
  security_group_id    = module.security_group.security_group_id
  key_name             = var.key_name
  private_key_base64 = var.private_key_base64
  instance_type = var.instance_type
  ami_id = var.ami_id
}





### ALB Module variables.tf


