// ecs-pipeline.groovy - Unified ECS pipeline for apply, switch, and rollback operations

@Library('jenkins-shared-library') _

pipeline {
    agent any
    
    parameters {
        choice(
            name: 'OPERATION',
            choices: ['APPLY', 'SWITCH', 'ROLLBACK'],
            description: 'Select the operation to perform: APPLY (deploy infrastructure), SWITCH (update and switch traffic), or ROLLBACK'
        )
    }
    
    triggers {
        githubPush()
    }
    
    environment {
        IMPLEMENTATION = 'ecs'
        AWS_REGION = 'us-east-1'
        AWS_CREDENTIALS_ID = 'aws-credentials'
        ECR_REPO_NAME = 'blue-green-app'
        CONTAINER_NAME = 'blue-green-container'
        CONTAINER_PORT = '80'
        DOCKERFILE = 'Dockerfile'
        APP_FILE = 'app.py'
        EMAIL_RECIPIENT = 'tanishqparab2001@gmail.com'
        REPO_URL = 'https://github.com/TanishqParab/blue-green-deployment-ecs-test'
        REPO_BRANCH = 'main'
        TF_WORKING_DIR = '/var/lib/jenkins/workspace/blue-green-deployment-ptest-ecs/blue-green-deployment'
    }
    
    stages {
        stage('Determine Operation') {
            steps {
                script {
                    // Determine operation - if triggered by GitHub push, use SWITCH
                    env.SELECTED_OPERATION = params.OPERATION ?: 'APPLY'  // Default to APPLY if null
                    if (currentBuild.getBuildCauses('hudson.triggers.SCMTrigger$SCMTriggerCause').size() > 0) {
                        echo "Build triggered by GitHub push - automatically using SWITCH operation"
                        env.SELECTED_OPERATION = 'SWITCH'
                    } else {
                        echo "Executing ECS ${env.SELECTED_OPERATION} pipeline..."
                    }
                }
            }
        }
        
        stage('Execute Apply') {
            when {
                expression { env.SELECTED_OPERATION == 'APPLY' }
            }
            steps {
                script {
                    // Call the shared library function directly
                    basePipeline([
                        implementation: env.IMPLEMENTATION,
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        ecrRepoName: env.ECR_REPO_NAME,
                        containerName: env.CONTAINER_NAME,
                        containerPort: env.CONTAINER_PORT,
                        dockerfile: env.DOCKERFILE,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ])
                }
            }
        }
        
        stage('Execute Switch') {
            when {
                expression { env.SELECTED_OPERATION == 'SWITCH' }
            }
            steps {
                script {
                    // Call the shared library function directly
                    switchPipeline([
                        implementation: env.IMPLEMENTATION,
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        ecrRepoName: env.ECR_REPO_NAME,
                        containerName: env.CONTAINER_NAME,
                        containerPort: env.CONTAINER_PORT,
                        dockerfile: env.DOCKERFILE,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ])
                }
            }
        }
        
        stage('Execute Rollback') {
            when {
                expression { env.SELECTED_OPERATION == 'ROLLBACK' }
            }
            steps {
                script {
                    // Call the shared library function directly
                    rollbackPipeline([
                        implementation: env.IMPLEMENTATION,
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        ecrRepoName: env.ECR_REPO_NAME,
                        containerName: env.CONTAINER_NAME,
                        containerPort: env.CONTAINER_PORT,
                        dockerfile: env.DOCKERFILE,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ])
                }
            }
        }
    }
}
