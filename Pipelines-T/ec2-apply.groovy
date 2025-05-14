// ec2-apply.groovy

@Library('jenkins-shared-library') _

// Load the EC2 pipeline script
def ec2Pipeline = load("${WORKSPACE}/Pipelines-T/jenkins-shared-library/resources/templates/ec2-apply-pipeline.groovy")

// Call the pipeline with custom configuration
ec2Pipeline([
    tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job-apply/blue-green-deployment",
    emailRecipient: "tanishqparab2001@gmail.com",
    sshKeyId: "blue-green-key"
])
