

resource "aws_instance" "blue" {
  ami           = "ami-05b10e08d247fb927"
  instance_type = var.instance_type
  key_name      = "blue-green-key-pair"  # Use the existing key in AWS
  subnet_id     = var.subnet_id
  security_groups = [var.ec2_security_group_id]

  tags = {
    Name = "Blue-Instance"
  }

  provisioner "file" {
    source      = "C:/Users/TANISHQ PARAB/Desktop/tanishq/Encora-Projects/blue-green-deployment/modules/ec2/scripts/install_dependencies.sh"
    destination = "/home/ec2-user/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "C:/Users/TANISHQ PARAB/Desktop/tanishq/Encora-Projects/blue-green-deployment/modules/ec2/scripts/app.py"
    destination = "/home/ec2-user/app.py"
  }

  provisioner "file" {
    source      = "C:/Users/TANISHQ PARAB/Desktop/tanishq/Encora-Projects/blue-green-deployment/Jenkinsfile"
    destination = "/home/ec2-user/Jenkinsfile"
  }


  provisioner "remote-exec" {
    inline = [
      "chmod +x /home/ec2-user/install_dependencies.sh",
      "sudo /home/ec2-user/install_dependencies.sh"
    ]
  }

connection {
  type        = "ssh"
  user        = "ec2-user"  # Change if using a different AMI
  private_key = file("blue-green-key.pem")
  host        = self.public_ip
}
}

resource "aws_instance" "green" {
  ami           = "ami-05b10e08d247fb927"
  instance_type = var.instance_type
  key_name      = "blue-green-key-pair"  # Use the existing key in AWS
  subnet_id     = var.subnet_id
  security_groups = [var.ec2_security_group_id]
  
  tags = {
    Name = "Green-Instance"
  }

    provisioner "file" {
    source      = "C:/Users/TANISHQ PARAB/Desktop/tanishq/Encora-Projects/blue-green-deployment/modules/ec2/scripts/install_dependencies.sh"
    destination = "/home/ec2-user/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "C:/Users/TANISHQ PARAB/Desktop/tanishq/Encora-Projects/blue-green-deployment/modules/ec2/scripts/app.py"
    destination = "/home/ec2-user/app.py"
  }

  provisioner "file" {
    source      = "C:/Users/TANISHQ PARAB/Desktop/tanishq/Encora-Projects/blue-green-deployment/Jenkinsfile"
    destination = "/home/ec2-user/Jenkinsfile"
  }

  provisioner "remote-exec" {
    inline = [
      "chmod +x /home/ec2-user/install_dependencies.sh",
      "sudo /home/ec2-user/install_dependencies.sh"
    ]
  }

 connection {
  type        = "ssh"
  user        = "ec2-user"  # Change if using a different AMI
  private_key = file("blue-green-key.pem")
  host        = self.public_ip
}
}
