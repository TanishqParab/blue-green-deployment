# AWS Region
aws_region = "us-east-1"

#############################
# VPC Configuration
#############################
vpc_cidr                = "10.0.0.0/16"
public_subnet_cidrs     = ["10.0.1.0/24", "10.0.2.0/24"]
availability_zones      = ["us-east-1a", "us-east-1b"]
vpc_name                = "Main VPC"
enable_dns_support      = true
enable_dns_hostnames    = true
subnet_name_prefix      = "Public Subnet"
igw_name                = "Main IGW"
route_table_name        = "Public Route Table"
map_public_ip_on_launch = true
internet_cidr_block     = "0.0.0.0/0"

#############################
# Security Group
#############################
security_group_name        = "EC2 Security Group"
security_group_description = "Security group for EC2 instances"
ingress_rules = [
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
egress_from_port   = 0
egress_to_port     = 0
egress_protocol    = "-1"
egress_cidr_blocks = ["0.0.0.0/0"]
egress_description = "Allow all outbound traffic"
sg_additional_tags = {}

#############################
# ALB Configuration
#############################
alb_name                = "blue-green-alb"
internal                = false
load_balancer_type      = "application"
blue_target_group_name  = "blue-tg"
green_target_group_name = "green-tg"
target_group_port       = 5000
target_group_protocol   = "HTTP"
target_type             = "instance"
listener_port           = 80
listener_protocol       = "HTTP"
health_check_path       = "/health"
health_check_interval   = 30
health_check_timeout    = 5
healthy_threshold       = 3
unhealthy_threshold     = 2
health_check_matcher    = "200"
blue_weight             = 100
green_weight            = 0
stickiness_enabled      = true
stickiness_duration     = 300

#############################
# EC2 Configuration
#############################
key_name            = "blue-green-key-pair"
private_key_base64  = "LS0tLS1CRUdJTiBSU0EgUFJJVkFURSBLRVktLS0tLQpNSUlKS0FJQkFBS0NBZ0VBMncxcW5DTnN4R2lkOXV0Nlp2KzBXSXViMGJocm53Vm5TSkxtUUJ2RitPV3g5TjBrCldxa3R1b3BJY1JuNzhxd0NobkpveDNoN2tXRHovdjArYkFvUFV4OW45OUI1Y3VlMytQbUdMeFVlS2RxNGFNQzgKV1NId0E3OHJXa1BGOTRyRWh6cWsxYUxHN29kVFR3TGh6ZDA5RXZocDdpaWxSS1pud2UyclM0RGtBa1ErSFRqRgpOb2Y0WURXN0ZWcGVJUG5rZVlEdmc3NGd2eVA5VG1rakhKWTZQMWFFb0p1UlBqeU1JOGlYdDdJaklEeU9NTURiCjRDdFhlTHBzNUp5bHRKRjdlUXN0ZXhHNkJITjFjbTFndFc4VG5lVUI1SWxrcCtaNHY3Q0tEZm9IQkM2QWJUNWQKRTFIbno0T2gySmtyWTFXNFBnOCt4Z3N4eHJiWXovK0p3SWJqK3IvcTFlL2RNMklQaEVzdXdidWtIbGZCRnI4RwpmK3ZWcjVBMUxMTVZnOXBjN1hrMTlkM2NRRXBOTUdqZ0kxTTNnbU5hZjlJZDlMZmFnWFZpQjdTQ0o4SnhhUlhjCmZFWE1LbmdJeVBuTU4yZGtaZXBETzJDMDQwNnBPMXdDa2J1UUMwakM4Ti8wbHpvWHRuOFhBTWFrMkJHdVdZS0QKRTJOTGcwL244QXRwWXpsMjVTV0VMdHgwVmdidmNiMFVOQ0JXVUhQcmlia1FSZ3dEUDExWEpyUlpCcXZvM00rNgozM1Rwbzg1eWNVTS9EWkcyaWxhNlVPMUIwd1h6S2VoUEhXd2lwSE40OE4rdjd3bVpITFAzUUJkTTNrOURxV3dZCmJRa3lTT1JQYXRuSm9TWFJqYWNYcTAyQjhqaFVQUWxvOWRSdnFHWnVyak5Yc0dRZzNpeFFpRER0RzNrQ0F3RUEKQVFLQ0FnQUNLRytOam5sSXRZMmgxRGZLV2poRys0Z2JVSzFwdllKREdDUmh5d3hBRzVZdFZ2emYwa1VYcm50UQpkdXl4R3pIeXJGK2RJSElhTUdueThBQjhqTHhTS2EvcTVIQS8yaW5KTDM4YmlXSVkwRFZySGNQMVBsVDRtbnBsCk94L3hCSHBUYVRmY3ZXdm5oMmlDRWFHVEZ6djk2dm5UTFc0VVh5M01QcWpHZDRSM2c3L1hacHJsd3NEbkJMeDkKTlR6U1p4ZlJ2UndPOEpGdXhKNWZGb0RRckNleWZrb1Q4WGhrdERDK3ZRQUdvS0FCTml1QjdqSjBVc1Q3dE4xMApBcG1NemZhWkRvdkNCNzZOQXV5c0pnankvSjlGT2M5ekZvbnA4QWF1UDhGYWFpVkZ6S1g1L1locDgyOTh6enVKCjBGZDV0T3RaM0NsV2h0OTBpVkpaT1RlY2tJK2dJUFpSazZ6R1NxelNyb3o3TGpTU2FUc244aE9nVjlNN3MvZkEKMk9hUDVjYjdjdG9hVnR0NkwwM0NJSTJNSVpIKzlHbDRaSWN6N21oL2VyWjh3LzNGR1g5RTBLNzhmT29rOHNFRgpQbWxnOWpzQ1UxMHYwTlViSnowVUZ3Zmx2V2Jta2xTQU9uZ2J2Z2h3UnVFZlBVMXQwSTNta01VdE9CS1hCa08zClp3bTZHMzBEN0ExNkk5QzU5bkRRK2s3TXVVQmRiejdHcHBHbVFiM2VET1JZUWlyVHJJQ01ZaXJHQUYvU1A4SDIKVEpBVnNmL0QzcElDN1hiMkhMOHYwOUd3YUV5VWpPRkRQaUNLQkJFVVJhNWNIVER6dWZ0eWZQc28zTDZOckNmVAprMkhvM1phSklISTFXc1U2OUNQUHRLdjFKblNMWTVGS0hUL256cWg5ZWpZMERmN0lnUUtDQVFFQStHOTV4SEg1CnUwaUpmNGtKaFppSmhPeUwzQU1IYmNSSVFmU0JaZHlNUFh1NVpSTm8vZ3JxVE1GR3hzelhZTmxwSGVnTTE4enoKT2R1bEUrcDNxc2J5SlUrcFRJQ3dSWUVsQnpCUjN4T0Q5TC84ZE9sL1NPN3Q0QXZ5Y3hVMENhdVFva01CTmVRNwpvY1pWc3ZyYktVZFh6aGdLbW56a3hIbFpnNXYvM0ZGcm1pUEdvdEYza1RLSHV1Sm5SZVFFTHl4clZpM0xhMm5SCkhQY2IyNjk1KzVVNlJxamhwMkNuWnY0dElvTEVhYjhwb3BqeWZ4dUx1dUd3TzZodjVFMW5SYmt0aHFOV2x2VjUKTFVubC9VS2JCVk9mZFZ0cHBNTzQ4NEpIWHFlZ3pLUzdQWUNFeGhuSmpvS2c3UzhXZ3hZbWpaU1p1bDgvbk1MMApFeldlR0JLdXRIbWgrUUtDQVFFQTRiam5US0xtTkh4a2Q1N2pIQVhpaFB6Ulg4bkNqNVZoMzM1WjJ4eVk2TTk0CkRDOHB0alR4ajRsRGVCTWY0dzBtY1QzOUErVGZXUXh2d0V3QkRhSCtvK0FOT1puN1V5bHI0WkU5Kzh0V2VLYXIKYmVrenJ4L1RHSlB2Z0lBRXptOTMxVFhRYzF3b01MVEgwSSt2RmkrYmgvTStWV3AwZ2FkWnlOU3JiN2xLanF3RwpER0Rid2F0M1MzNkdySnVVcjZkcTJNTmlxN1FiUS9PV2NVcHZHOEs0citlbk5aTE40T0x3YTRTWCtSNCt6VWRnCjVCdXNYOTYrQ3RyVWxHNUVUaXJmOThOZEtsVXhlQ2JWUkJ3cXVaK3FicFMrc1IzeWhqdHArSEpxZFR3eHNUMTQKZ1V4NGg0QkxXUVJtYTM1eWh0MS9pUXZ3b1M0UXN2QkFpMUtjUzJXbGdRS0NBUUFabnkyWXhBUjBlME9yQXBBWAoxaWFBcmdDeW5TRmNBYjFPQ0JCOFYrV2l4YXJXTU0xSVBnbnlCcER0R2Qwd29OdUZlUlF5QVhJb1NtM1pBdnA2CmczQWZ4dnAzNkdIRm1VOGZVYTF2NjB4VnBxTTd6NFVRR1l3dzZpcUVFZkMrK3BHOUdsbjZtK0pHaWZUMnM0WjgKMkYzRzVKWGJYdndkQTBMbkh1U2hiVWhDcW1QbkVPRmErVElrWlFzdm14ZVBZZTVrQWU4VDBlTCtNTUlQd3lZNgpleVo2ZVJwa3I4UTBEQXpOblZ6eVp3TzlRRGJxUXdZRExSbUczWlZFbjNNQ0x0bnlJOUJmVzB1M0R3TUlQcUZNClNGYU92UEhGUzJZOHZ2ZnJYREJxU3FjQTdjdER2dzhaZ29Ga1ZOSG1qUmRHek1lYUFBN0lkUmJGRUdlUXBnU2MKbWxySkFvSUJBQTM2NE1CN1dseW4wNlVnL3hudU4yQmJORENGazNwSEd6KzNXS05jZXcvNFFZd25vNks0VnJtNApHNmlsTHBWbWJCb1paOEZFL0p4TVMrT1NFWUtocE43TGNxWTlwalk1VzRnbDhidlZsUzUxekNwTGhqcnpjcVNVCkRRSmRhMjdKc3BkTzlQRWdKUkVYTVVUMmtUYURqbE4yT2tjYUI4czc3VENtRTFRaEdzQUpZWHFFeVRlT2doMzMKNFNseG5WemZ0cHRrUm9reDUzcG03TXRwZThZeFlqVHEyUTFWWVZEclhVNmJjTG9xS0dPWVp5VFpuZXgySkRrUgo0cGFxMmFvcHQ2Tmx3ekJyQkZ4WHMxKzdpdDNpU0xEK24yemkyUEY5WG92WHNrWStpeWxhRUV1WnkrRkFqZW9lCmZxVnJ1SFluNDgwK0l4SW9nenBCN1ExeitXQW1GSUVDZ2dFQkFKVHErZ3NXdnZMeDBHS25uZmMwMGx3c2phTWYKektCdUs4U2Rta3ZvU0tPaHFKQUNnT0g2cVlLRU51NldJdHY4Sk1DMCs2c2ZBd2IxaElzK2ZWZzNhM2x2R2Q4aApCZXplaUhmWkREKzZpb3lRUCtpM3pJRUFFQS95NXJqVFdSbzF4cnBYTzFWcGZwZVRDck5iMk1GNi9sSVNXZ1B4CmRlUmdacFdkalF6Smd4Tzk1UDZwWGFBdStHa2tIS3VVR0hjbW1BaVVDYU44Q0c3Rmk3eVk2N1k4cGxxMjJsT1YKejdFUG9LTm5CUDVzVzhiSjY5cG9yZW5WVWJ4WmdxVUIxMzhsOEVWb1RKT3RDVjNsdmxkazFFVXk2UmdQY0ZkeQplblh3ZTI0Z25xbjFoT1Y4bUxEbjlCdnpRUW5kYXBMMVZHUjJpUEMzRnpTQ25nUUxDby9DSnlRYW9FZz0KLS0tLS1FTkQgUlNBIFBSSVZBVEUgS0VZLS0tLS0K"
public_key_path     = "/var/lib/jenkins/workspace/blue-green-deployment-job/blue-green-deployment/blue-green-key.pub"
instance_type       = "t3.micro"
ami_id              = "ami-05b10e08d247fb927"
blue_instance_name  = "Blue-Instance"
green_instance_name = "Green-Instance"
environment_tag     = "Blue-Green"
ssh_user            = "ec2-user"
ec2_additional_tags = {}

#############################
# Auto Scaling Group
#############################
asg_name                    = "blue_green_asg"
min_size                    = 1
max_size                    = 2
desired_capacity            = 1
launch_template_name_prefix = "blue-green-launch-template"
associate_public_ip_address = true
health_check_type           = "ELB"
