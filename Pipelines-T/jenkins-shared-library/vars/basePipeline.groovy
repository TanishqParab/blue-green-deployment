// vars/basePipeline.groovy

def call(Map config) {
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


        stages {
            stage('Initialize') {
                steps {
                    script {
                        def buildId = currentBuild.number
                        echo "Current Build ID: ${buildId}"
                        
                        // Set Execution Type
                        env.EXECUTION_TYPE = 'SKIP'
                        if (params.MANUAL_BUILD == 'DESTROY') {
                            echo "❌ Destroy requested. Running destroy stage only."
                            env.EXECUTION_TYPE = 'DESTROY'
                        } else if (params.MANUAL_BUILD == 'YES') {
                            echo "🛠️ Manual build requested. Running Terraform regardless of changes."
                            env.EXECUTION_TYPE = 'MANUAL_APPLY'
                        }
                        echo "Final Execution Type: ${env.EXECUTION_TYPE}"
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
                        terraformInit(config)
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
                        approvals.terraformApplyApproval(config)
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
                    }
                }
            }
            
            stage('Register EC2 Instances to Target Groups') {
                when {
                    allOf {
                        expression { config.implementation == 'ec2' }
                        expression { params.MANUAL_BUILD != 'DESTROY' }
                    }
                }
                steps {
                    script {
                        ec2Utils.registerInstancesToTargetGroups(config)
                    }
                }
            }


            stage('Manual Approval for Destroy') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' }
                }
                steps {
                    script {
                        approvals.terraformDestroyApproval(config)
                    }
                }
            }
            
            stage('Clean Resources') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' && config.implementation == 'ecs' }
                }
                steps {
                    script {
                        ecsUtils.cleanResources(config)
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
