// ec2-apply.groovy

@Library('jenkins-shared-library') _

// Define a simple pipeline directly
pipeline {
    agent any
    
    environment {
        AWS_REGION = "us-east-1"
        AWS_CREDENTIALS_ID = "aws-credentials"
        TF_WORKING_DIR = "/var/lib/jenkins/workspace/blue-green-deployment-job-apply/blue-green-deployment"
        IMPLEMENTATION = "ec2"
        SSH_KEY_ID = "blue-green-key"
        APP_FILE = "app.py"
    }
    
    parameters {
        choice(name: 'MANUAL_BUILD', choices: ['YES', 'DESTROY', 'NO'], description: 'YES: Run Terraform, DESTROY: Destroy Infra, NO: Auto Deploy App Changes')
    }

    triggers {
        githubPush()
    }

    stages {
        stage('Initialize') {
            steps {
                script {
                    def pipelineUtils = load("Pipelines-T/jenkins-shared-library/vars/pipelineUtils.groovy")
                    def terraformInit = load("Pipelines-T/jenkins-shared-library/vars/terraformInit.groovy")
                    def terraformPlan = load("Pipelines-T/jenkins-shared-library/vars/terraformPlan.groovy")
                    def terraformApply = load("Pipelines-T/jenkins-shared-library/vars/terraformApply.groovy")
                    def terraformDestroy = load("Pipelines-T/jenkins-shared-library/vars/terraformDestroy.groovy")
                    def manualApproval = load("Pipelines-T/jenkins-shared-library/vars/manualApproval.groovy")
                    def ec2Utils = load("Pipelines-T/jenkins-shared-library/vars/ec2Utils.groovy")
                    
                    def config = [
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        repoUrl: 'https://github.com/TanishqParab/blue-green-deployment',
                        repoBranch: 'main',
                        emailRecipient: "tanishqparab2001@gmail.com",
                        implementation: env.IMPLEMENTATION,
                        sshKeyId: env.SSH_KEY_ID,
                        appFile: env.APP_FILE
                    ]
                    
                    // Set execution type
                    pipelineUtils.setExecutionType()
                    
                    // Continue with the rest of the pipeline
                    if (env.EXECUTION_TYPE != 'ROLLBACK') {
                        echo "Checking out the latest code..."
                        checkout scmGit(branches: [[name: 'main']], 
                                      extensions: [], 
                                      userRemoteConfigs: [[url: config.repoUrl]])
                    }
                    
                    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
                        terraformInit.call(config.tfWorkingDir)
                        terraformPlan.call(config)
                        manualApproval.call(config)
                        terraformApply.call(config)
                        ec2Utils.deployApplication(config)
                    }
                    
                    if (params.MANUAL_BUILD == 'DESTROY') {
                        pipelineUtils.approveDestroy(config)
                        terraformDestroy.call(config)
                    }
                }
            }
        }
    }
}
