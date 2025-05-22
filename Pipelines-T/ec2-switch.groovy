// ec2-switch.Jenkinsfile

@Library('jenkins-shared-library') _

// Call the EC2 switch pipeline with custom configuration
ec2SwitchPipeline([
    tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job-ec2-switch-test/blue-green-deployment-ec2",
    emailRecipient: "tanishqparab2001@gmail.com",
    sshKeyId: "blue-green-key"
])
