// ecs-apply.Jenkinsfile

@Library('jenkins-shared-library') _

// Import the ECS pipeline template
def ecsPipeline = library('jenkins-shared-library').resources.templates.'ecs-apply-pipeline'

// Call the pipeline with custom configuration
ecsPipeline([
    tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job-ecs-test-apply/blue-green-deployment",
    emailRecipient: "tanishqparab2001@gmail.com",
    ecrRepoName: "blue-green-app",
    varFile: "terraform.tfvars"
])
