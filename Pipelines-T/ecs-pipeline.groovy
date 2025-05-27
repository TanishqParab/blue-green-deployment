// ecs-pipeline.groovy - Unified ECS pipeline for apply, switch, and rollback operations

@Library('jenkins-shared-library') _

// Get the operation type from the job parameter
def call(Map params = [:]) {
    // Default configuration for all ECS operations
    def config = [
        implementation: 'ecs',
        awsRegion: 'us-east-1',
        awsCredentialsId: 'aws-credentials',
        ecrRepoName: 'blue-green-app',
        containerName: 'blue-green-container',
        containerPort: '80',
        dockerfile: 'Dockerfile',
        appFile: 'app.py',
        emailRecipient: 'tanishqparab2001@gmail.com',
        repoUrl: 'https://github.com/TanishqParab/blue-green-deployment-ecs-test',
        repoBranch: 'main'
    ]
    
    // Merge with user-provided parameters
    config = config + params
    
    // Determine which operation to perform based on the OPERATION parameter
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
        
        stages {
            stage('Execute Pipeline') {
                steps {
                    script {
                        // Determine operation - if triggered by GitHub push, use SWITCH
                        def operation = params.OPERATION
                        if (currentBuild.getBuildCauses('hudson.triggers.SCMTrigger$SCMTriggerCause').size() > 0) {
                            echo "Build triggered by GitHub push - automatically using SWITCH operation"
                            operation = 'SWITCH'
                        } else {
                            echo "Executing ECS ${operation} pipeline..."
                        }
                        
                        // Use ECS-specific working directory
                        if (!config.tfWorkingDir) {
                            config.tfWorkingDir = "/var/lib/jenkins/workspace/blue-green-deployment-ptest-ecs/blue-green-deployment"
                        }
                        
                        // Call the appropriate pipeline based on the operation
                        switch(operation) {
                            case 'APPLY':
                                ecsPipeline(config)
                                break
                            case 'SWITCH':
                                ecsSwitchPipeline(config)
                                break
                            case 'ROLLBACK':
                                ecsRollbackPipeline(config)
                                break
                            default:
                                error "Invalid operation: ${operation}"
                        }
                    }
                }
            }
        }
    }
}

// Execute the pipeline
call()
