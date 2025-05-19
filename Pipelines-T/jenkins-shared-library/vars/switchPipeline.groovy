// vars/switchPipeline.groovy

def call(Map config) {
    pipeline {
        agent any
        
        environment {
            AWS_REGION = "${config.awsRegion}"
            AWS_CREDENTIALS_ID = "${config.awsCredentialsId}"
            TF_WORKING_DIR = "${config.tfWorkingDir}"
            IMPLEMENTATION = "${config.implementation}"
            
            // EC2 specific
            SSH_KEY_ID = "${config.sshKeyId}"
            APP_FILE = "${config.appFile}"
            
            // ECS specific
            ECR_REPO_NAME = "${config.ecrRepoName}"
            CONTAINER_PORT = "${config.containerPort}"
            DOCKERFILE = "${config.dockerfile ?: 'Dockerfile'}"
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

            stage('Checkout') {
                when {
                    expression { 
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                        (config.implementation == 'ecs')
                    }
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
            
            stage('Detect Changes') {
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            ec2Utils.detectChanges(config)
                        } else if (config.implementation == 'ecs') {
                            ecsUtils.detectChanges(config)
                        }
                    }
                }
            }

            
            stage('Fetch Resources') {
                when {
                    expression { 
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            ec2Utils.fetchResources(config)
                        } else if (config.implementation == 'ecs') {
                            // fetchResources returns a map with keys like IDLE_TG_ARN, LISTENER_ARN, IDLE_ENV
                            def resourceInfo = ecsUtils.fetchResources(config)

                            // Store these in env variables or a map to use in later stages
                            env.IDLE_TG_ARN = resourceInfo.IDLE_TG_ARN
                            env.LISTENER_ARN = resourceInfo.LISTENER_ARN
                            env.IDLE_ENV = resourceInfo.IDLE_ENV

                            // Also update the config map with these so next stage can use it
                            config.IDLE_TG_ARN = env.IDLE_TG_ARN
                            config.LISTENER_ARN = env.LISTENER_ARN
                            config.IDLE_ENV = env.IDLE_ENV
                        }
                    }
                }
            }

            stage('Ensure Target Group Association') {
                when {
                    expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
                }
                steps {
                    script {
                        // Pass the updated config with required parameters explicitly
                        ecsUtils.ensureTargetGroupAssociation([
                            IDLE_TG_ARN: config.IDLE_TG_ARN,
                            LISTENER_ARN: config.LISTENER_ARN,
                            IDLE_ENV: config.IDLE_ENV
                        ])
                    }
                }
            }


            stage('Manual Approval Before Switch Traffic EC2') {
                when { expression { config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY' } }
                steps {
                    script {
                        approvals.switchTrafficApprovalEC2(config)
                    }
                }
            }
            
            stage('Update Application') {
                when {
                    expression { 
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            ec2Utils.updateApplication(config)
                        } else if (config.implementation == 'ecs') {
                            ecsUtils.updateApplication(config)
                        } else {
                            error "Unsupported implementation type: ${config.implementation}"
                        }
                    }
                }
            }

            stage('Deploy to Blue EC2 Instance') {
                when {
                    expression { config.implementation == 'ec2' }
                }
                steps {
                    script {
                        ec2Utils.deployToBlueInstance(config)
                    }
                }
            }

            
            stage('Test Environment') {
                when {
                    expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
                }
                steps {
                    script {
                        ecsUtils.testEnvironment(config)
                    }
                }
            }
            
            stage('Manual Approval Before Switch Traffic ECS') {
                when { expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' } }
                steps {
                    script {
                        approvals.switchTrafficApprovalECS(config)
                    }
                }
            }

            stage('Fetch ALB Resources') {
                when {
                    expression { 
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            ec2Utils.fetchResources(config)
                        } else if (config.implementation == 'ecs') {
                            // fetchResources returns a map with keys like IDLE_TG_ARN, LISTENER_ARN, IDLE_ENV
                            def resourceInfo = ecsUtils.fetchResources(config)

                            // Store these in env variables or a map to use in later stages
                            env.IDLE_TG_ARN = resourceInfo.IDLE_TG_ARN
                            env.LISTENER_ARN = resourceInfo.LISTENER_ARN
                            env.IDLE_ENV = resourceInfo.IDLE_ENV

                            // Also update the config map with these so next stage can use it
                            config.IDLE_TG_ARN = env.IDLE_TG_ARN
                            config.LISTENER_ARN = env.LISTENER_ARN
                            config.IDLE_ENV = env.IDLE_ENV
                        }
                    }
                }
            }

            
            stage('Switch Traffic') {
                when {
                    expression { 
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            // For EC2, just call switchTraffic with config as is
                            ec2Utils.switchTraffic(config)
                        } else if (config.implementation == 'ecs') {
                            // For ECS, fetch resources first
                            def resourceInfo = ecsUtils.fetchResources([
                                tfWorkingDir: env.TF_WORKING_DIR
                            ])
            
                            echo "🔄 Switching to ${resourceInfo.IDLE_ENV} with:"
                            echo "🔹 LISTENER_ARN: ${resourceInfo.LISTENER_ARN}"
                            echo "🔹 IDLE_TG_ARN : ${resourceInfo.IDLE_TG_ARN}"
            
                            ecsUtils.switchTraffic([
                                LISTENER_ARN: resourceInfo.LISTENER_ARN,
                                IDLE_TG_ARN : resourceInfo.IDLE_TG_ARN,
                                IDLE_ENV    : resourceInfo.IDLE_ENV
                            ])
                        }
                    }
                }
            }

            stage('Post-Switch Actions') {
                when {
                    expression {
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            // Call shared library method directly
                            ec2Utils.tagSwapInstances([
                                blueTag : 'Blue-Instance',
                                greenTag: 'Green-Instance'
                            ])
                        } else if (config.implementation == 'ecs') {
                            // Call shared library method directly with minimal info
                            ecsUtils.scaleDownOldEnvironment([
                                liveEnv: env.LIVE_ENV  // optional, just for logging
                            ])
                        }
                    }
                }
            }
        }
    }
}
