// resources/templates/base-pipeline.groovy

def call(Map config) {
    def defaults = [
        awsRegion: 'us-east-1',
        awsCredentialsId: 'aws-credentials',
        tfWorkingDir: "${WORKSPACE}/blue-green-deployment",
        repoUrl: '',
        repoBranch: 'main',
        emailRecipient: '',
        varFile: '',
        implementation: 'generic' // 'ec2' or 'ecs'
    ]
    
    config = defaults + config
    
    pipeline {
        agent any
        
        environment {
            AWS_REGION = "${config.awsRegion}"
            AWS_CREDENTIALS_ID = "${config.awsCredentialsId}"
            TF_WORKING_DIR = "${config.tfWorkingDir}"
            IMPLEMENTATION = "${config.implementation}"
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
                        def buildId = currentBuild.number
                        echo "Current Build ID: ${buildId}"
                    }
                }
            }
            
            stage('Set Execution Type') {
                steps {
                    script {
                        pipelineUtils.setExecutionType()
                    }
                }
            }
            
            stage('Checkout') {
                when {
                    expression { env.EXECUTION_TYPE != 'ROLLBACK' }
                }
                steps {
                    script {
                        echo "Checking out the latest code..."
                        checkout scmGit(branches: [[name: config.repoBranch]], 
                                        extensions: [], 
                                        userRemoteConfigs: [[url: config.repoUrl]])
                    }
                }
            }

            stage('Terraform Init') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        terraformInit(config.tfWorkingDir)
                    }
                }
            }

            stage('Terraform Plan') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        terraformPlan(config)
                    }
                }
            }

            stage('Manual Approval') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        manualApproval(config)
                    }
                }
            }

            stage('Apply Infrastructure') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        terraformApply(config)
                        
                        // Implementation-specific post-apply steps
                        if (config.implementation == 'ec2') {
                            ec2Utils.deployApplication(config)
                        } else if (config.implementation == 'ecs') {
                            ecsUtils.waitForServices(config)
                        }
                    }
                }
            }

            stage('Manual Approval for Destroy') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' }
                }
                steps {
                    script {
                        pipelineUtils.approveDestroy(config)
                    }
                }
            }
            
            stage('Clean Resources') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' }
                }
                steps {
                    script {
                        if (config.implementation == 'ecs') {
                            ecsUtils.cleanEcrRepository(config)
                        }
                    }
                }
            }
            
            stage('Destroy Infrastructure') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' }
                }
                steps {
                    script {
                        terraformDestroy(config)
                    }
                }
            }
        }
    }
}
