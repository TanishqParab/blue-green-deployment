variable "aws_region" {
  default = "us-east-1"
}

variable "vpc_cidr" {
  default = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "key_name" {
  description = "Name of the key pair"
  type        = string
  default     = "blue-green-key-pair"
}

variable "private_key_path" {
  default = "~/.ssh/my-key.pem"
}


variable "public_key_path" {
  description = "Path to the public key file"
  type        = string
  default     = "C:/Users/Tanishq.Parab/Desktop/Projects/blue-green-deployment/blue-green-key.pub"
}



variable "availability_zones" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b"]
}
/*variable "ssh_agent" {
  type    = bool
  default = true
  
} ////////////////////////*/

variable "instance_type" {
  default = "t2.micro"
}