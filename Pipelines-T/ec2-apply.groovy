// ec2-apply.Jenkinsfile

@Library('jenkins-shared-library') _

// Import the EC2 pipeline template
def ec2Pipeline = library('jenkins-shared-library').resources.templates.'ec2-apply-pipeline'

// Call the pipeline with custom configuration
ec2Pipeline([
    tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job-apply/blue-green-deployment",
    emailRecipient: "tanishqparab2001@gmail.com",
    sshKeyId: "blue-green-key"
])
