aws_region          = "us-east-1"
vpc_cidr            = "10.0.0.0/16"
public_subnet_cidrs = ["10.0.1.0/24", "10.0.2.0/24"]
availability_zones  = ["us-east-1a", "us-east-1b"]

key_name         = "blue-green-key-pair"
private_key_path =  "/var/lib/jenkins/blue-green-key.pem"
public_key_path  =  "/var/lib/jenkins/.ssh/blue-green-key.pub"

#ssh_agent        = true
#ssh_agent = true
