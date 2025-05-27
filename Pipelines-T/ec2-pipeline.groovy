// ec2-pipeline.groovy - Unified EC2 pipeline for apply, switch, and rollback operations

@Library('jenkins-shared-library') _

// Get the operation type from the job parameter
def call(Map params = [:]) {
    // Default configuration for all EC2 operations
    def config = [
        implementation: 'ec2',
        awsRegion: 'us-east-1',
        awsCredentialsId: 'aws-credentials',
        sshKeyId: 'blue-green-key',
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
                        echo "Executing EC2 ${params.OPERATION} pipeline..."
                        
                        // Use EC2-specific working directory
                        if (!config.tfWorkingDir) {
                            config.tfWorkingDir = "/var/lib/jenkins/workspace/blue-green-deployment-ptest-ec2/blue-green-deployment"
                        }
                        
                        // Call the appropriate pipeline based on the operation
                        switch(params.OPERATION) {
                            case 'APPLY':
                                ec2Pipeline(config)
                                break
                            case 'SWITCH':
                                ec2SwitchPipeline(config)
                                break
                            case 'ROLLBACK':
                                ec2RollbackPipeline(config)
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