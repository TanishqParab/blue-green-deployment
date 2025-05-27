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
    
    stages {
        stage('Determine Operation') {
            steps {
                script {
                    // Determine operation - if triggered by GitHub push, use SWITCH
                    def operation = params.OPERATION ?: 'APPLY'  // Default to APPLY if null
                    if (currentBuild.getBuildCauses('hudson.triggers.SCMTrigger$SCMTriggerCause').size() > 0) {
                        echo "Build triggered by GitHub push - automatically using SWITCH operation"
                        operation = 'SWITCH'
                    } else {
                        echo "Executing ECS ${operation} pipeline..."
                    }
                    
                    // Store the operation for later stages
                    env.SELECTED_OPERATION = operation
                }
            }
        }
        
        stage('Execute Operation') {
            steps {
                script {
                    def jobName = ""
                    
                    // Determine which job to call based on the operation
                    switch(env.SELECTED_OPERATION) {
                        case 'APPLY':
                            jobName = "blue-green-deployment-job"
                            break
                        case 'SWITCH':
                            jobName = "blue-green-deployment-job-ecs-switch-test"
                            break
                        case 'ROLLBACK':
                            jobName = "blue-green-deployment-job-ecs-rollback-test"
                            break
                        default:
                            error "Invalid operation: ${env.SELECTED_OPERATION}"
                    }
                    
                    // Build the job
                    echo "Triggering job: ${jobName}"
                    build job: jobName, wait: true
                }
            }
        }
    }
}
