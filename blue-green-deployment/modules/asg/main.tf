resource "aws_launch_template" "app" {
  name_prefix   = "blue-green-launch-template"
  image_id      = "ami-05b10e08d247fb927"
  instance_type = "t3.micro"
  key_name      = var.key_name

  network_interfaces {
    associate_public_ip_address = true
    security_groups = [var.security_group_id]
  }

    # Assign tags directly in the launch template
  tag_specifications {
    resource_type = "instance"

    tags = {
      Name = "Blue-Instance"
    }
  }

  tag_specifications {
    resource_type = "instance"

    tags = {
      Name = "Green-Instance"
    }
  }


  user_data = base64encode(<<EOF
#!/bin/bash
# Update packages
sudo yum update -y

# Install Git, Python, and Flask
sudo yum install -y git python3
sudo pip3 install flask

# Clone your GitHub repository
mkdir -p /home/ec2-user/app
cd /home/ec2-user/app
git clone https://github.com/TanishqParab/blue-green-deployment.git .

# Set permissions
sudo chown -R ec2-user:ec2-user /home/ec2-user/app

# Create a Flask systemd service
cat <<EOL | sudo tee /etc/systemd/system/flask-app.service
[Unit]
Description=Flask App
After=network.target

[Service]
User=ec2-user
WorkingDirectory=/home/ec2-user/app
ExecStart=/usr/bin/python3 /home/ec2-user/app/blue-green-deployment/modules/ec2/scripts/app.py
Restart=always

[Install]
WantedBy=multi-user.target
EOL

# Reload systemd and enable Flask service
sudo systemctl daemon-reload
sudo systemctl enable flask-app
sudo systemctl start flask-app
EOF
  )
}



resource "aws_autoscaling_group" "blue_green_asg" {
  name                      = "blue-green-asg"
  min_size                  = 2
  max_size                  = 2
  desired_capacity          = 2
  vpc_zone_identifier       = var.subnet_ids

  health_check_type         = "EC2"
  health_check_grace_period = 300
  force_delete              = true

  # Explicitly defining the tags for Blue and Green instances
  tag {
    key                 = "Name"
    value               = "Blue-Instance"
    propagate_at_launch = true
  }

  tag {
    key                 = "Name"
    value               = "Green-Instance"
    propagate_at_launch = true
  }


  launch_template {
    id      = aws_launch_template.app.id
    version = "$Latest"
  }

  target_group_arns = [ 
    var.blue_target_group_arn,
    var.var.green_target_group_arn
   ]


  lifecycle {
    ignore_changes = [ target_group_arns ]
  }
}
