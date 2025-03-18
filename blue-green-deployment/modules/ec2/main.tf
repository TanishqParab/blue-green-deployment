resource "aws_instance" "blue" {
  ami           = "ami-05b10e08d247fb927"
  instance_type = var.instance_type
  key_name      = var.key_name
  subnet_id     = var.subnet_id
  security_groups = [var.ec2_security_group_id]

  tags = {
    Name        = "Blue-Instance"
    Environment = "Blue-Green"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/install_dependencies.sh"
    destination = "/tmp/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app.py"
    destination = "/tmp/app.py"
  }

  provisioner "file" {
    source      = "${path.root}/Jenkinsfile"
    destination = "/tmp/Jenkinsfile"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo chmod +x /tmp/install_dependencies.sh",
      "sudo mv /tmp/install_dependencies.sh /home/ec2-user/install_dependencies.sh",
      "sudo chown ec2-user:ec2-user /home/ec2-user/install_dependencies.sh",
      "sudo /home/ec2-user/install_dependencies.sh"
    ]
  }

  connection {
    type        = "ssh"
    user        = "ec2-user"
    private_key = base64decode(var.private_key_base64)
    host        = self.public_ip
  }
}

resource "aws_instance" "green" {
  ami           = "ami-05b10e08d247fb927"
  instance_type = var.instance_type
  key_name      = var.key_name
  subnet_id     = var.subnet_id
  security_groups = [var.ec2_security_group_id]

  tags = {
    Name        = "Green-Instance"
    Environment = "Blue-Green"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/install_dependencies.sh"
    destination = "/tmp/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app.py"
    destination = "/tmp/app.py"
  }

  provisioner "file" {
    source      = "${path.root}/Jenkinsfile"
    destination = "/tmp/Jenkinsfile"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo chmod +x /tmp/install_dependencies.sh",
      "sudo mv /tmp/install_dependencies.sh /home/ec2-user/install_dependencies.sh",
      "sudo chown ec2-user:ec2-user /home/ec2-user/install_dependencies.sh",
      "sudo /home/ec2-user/install_dependencies.sh"
    ]
  }

  connection {
    type        = "ssh"
    user        = "ec2-user"
    private_key = base64decode(var.private_key_base64)
    host        = self.public_ip
  }
}

output "module_path" {
  value = path.module
}
