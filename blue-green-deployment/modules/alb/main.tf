resource "aws_lb" "main" {
  name               = "blue-green-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.security_group_id]
  subnets            = var.subnet_ids  # Now it will receive a list of subnets
}


resource "aws_lb_target_group" "blue" {
  name     = "blue-tg"
  port     = 5000
  protocol = "HTTP"
  vpc_id   = var.vpc_id
}

resource "aws_lb_target_group" "green" {
  name     = "green-tg"
  port     = 5000
  protocol = "HTTP"
  vpc_id   = var.vpc_id
}

# Add ALB Listener
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "forward"
    forward {
      target_group {
        arn    = aws_lb_target_group.blue.arn
        weight = 100  # Initially, all traffic goes to Blue
      }
      target_group {
        arn    = aws_lb_target_group.green.arn
        weight = 0  # No traffic to Green initially
      }
      stickiness {
        enabled = true
        duration = 300
      }
    }
  }
}

