// vars/rollbackPipeline.groovy

def call(Map config) {
    pipeline {
        agent any
        
        parameters {
            choice(
                name: 'CONFIRM_ROLLBACK',
                choices: ['NO', 'YES'],
                description: 'Confirm you want to rollback to previous version?'
            )
        }
        
        environment {
            AWS_REGION = "${config.awsRegion ?: 'us-east-1'}"
            AWS_CREDENTIALS_ID = "${config.awsCredentialsId ?: 'aws-credentials'}"
            TF_WORKING_DIR = "${config.tfWorkingDir}"
            IMPLEMENTATION = "${config.implementation}"
            
            // EC2 specific
            SSH_KEY_ID = "${config.sshKeyId}"
            APP_FILE = "${config.appFile}"
            
            // ECS specific
            ECR_REPO_NAME = "${config.ecrRepoName}"
            CONTAINER_NAME = "${config.containerName ?: 'blue-green-container'}"
            CONTAINER_PORT = "${config.containerPort ?: '80'}"
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        if (params.CONFIRM_ROLLBACK == 'NO') {
                            currentBuild.result = 'ABORTED'
                            error('Rollback was not confirmed - aborting pipeline')
                        }
                        
                        echo "Starting rollback to previous version"
                        currentBuild.displayName = " #${currentBuild.number} - Rollback"
                        
                        // Set execution type for EC2
                        if (config.implementation == 'ec2') {
                            env.EXECUTION_TYPE = 'ROLLBACK'
                        }
                    }
                }
            }
            
            stage('Fetch Resources') {
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            ec2RollbackUtils.fetchResources(config)
                        } else if (config.implementation == 'ecs') {
                            ecsRollbackUtils.fetchResources(config)
                        }
                    }
                }
            }

            stage('Manual Approval Before Rollback EC2') {
                when {
                    expression { return config.implementation == 'ec2' }
                }
                steps {
                    script {
                        approvals.rollbackApprovalEC2(config)
                    }
                }
            }


            stage('Prepare Rollback') {
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            ec2RollbackUtils.prepareRollback(config)
                        } else if (config.implementation == 'ecs') {
                            ecsRollbackUtils.prepareRollback(config)
                        }
                    }
                }
            }
             
            
            stage('Test Rollback Environment') {
                when {
                    expression { config.implementation == 'ecs' }
                }
                steps {
                    script {
                        ecsRollbackUtils.testRollbackEnvironment(config)
                    }
                }
            }
            
            stage('Manual Approval Before Rollback ECS') {
                when {
                    expression { config.implementation == 'ecs' }
                }
                steps {
                    script {
                        approvals.rollbackApprovalECS(config)
                    }
                }
            }
            
            stage('Execute Rollback') {
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            ec2RollbackUtils.executeEc2Rollback(config)
                        } else if (config.implementation == 'ecs') {
                            ecsRollbackUtils.executeEcsRollback(config)
                        }
                    }
                }
            }
            
            stage('Post-Rollback Actions') {
                steps {
                    script {
                        if (config.implementation == 'ecs') {
                            ecsRollbackUtils.postRollbackActions(config)
                        }
                    }
                }
            }
        }
    }
}
