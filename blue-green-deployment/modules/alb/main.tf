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
  target_type = "instance"  # Ensuring it targets EC2 instances
  health_check {
    path                = "/"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 3
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group" "green" {
  name     = "green-tg"
  port     = 5000
  protocol = "HTTP"
  vpc_id   = var.vpc_id
  target_type = "instance"  # Ensuring it targets EC2 instances
  health_check {
    path                = "/"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 3
    unhealthy_threshold = 3
  }
}

# ✅ Corrected Listener Resource
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn  # ✅ Corrected Reference
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.blue.arn  # ✅ Corrected Reference
  }
}
