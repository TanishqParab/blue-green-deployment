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
        
        stages {
            stage('Execute Pipeline') {
                steps {
                    script {
                        echo "Executing ECS ${params.OPERATION} pipeline..."
                        
                        // Use ECS-specific working directory
                        if (!config.tfWorkingDir) {
                            config.tfWorkingDir = "/var/lib/jenkins/workspace/blue-green-deployment-ptest-ecs/blue-green-deployment"
                        }
                        
                        // Call the appropriate pipeline based on the operation
                        switch(params.OPERATION) {
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
                                error "Invalid operation: ${params.OPERATION}"
                        }
                    }
                }
            }
        }
    }
}

// Execute the pipeline
call()