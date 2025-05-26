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
                            echo "🔄 Updating application on EC2..."
                            ec2Utils.updateApplication(config)
                        } else if (config.implementation == 'ecs') {
                            echo "🔄 Updating application on ECS..."

                            // Run ECS update logic (discover ECS cluster, build & push image, update idle service)
                            ecsUtils.updateApplication(config)

                            // Dynamically set config values from environment
                            config.ecsCluster        = env.ECS_CLUSTER ?: ''
                            config.rollbackVersionTag = env.PREVIOUS_VERSION_TAG ?: ''
                            config.newImageUri       = env.IMAGE_URI ?: ''
                            config.activeEnv         = env.ACTIVE_ENV ?: ''
                            config.idleEnv           = env.IDLE_ENV ?: ''
                            config.idleService       = env.IDLE_SERVICE ?: ''

                            echo """
                            ✅ ECS Application Update Summary:
                            ----------------------------------
                            🧱 ECS Cluster        : ${config.ecsCluster}
                            🔵 Active Environment : ${config.activeEnv}
                            🟢 Idle Environment   : ${config.idleEnv}
                            ⚙️  Idle Service       : ${config.idleService}
                            🔁 Rollback Version   : ${config.rollbackVersionTag}
                            🚀 New Image URI      : ${config.newImageUri}
                            """
                        } else {
                            error "❌ Unsupported implementation type: ${config.implementation}"
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
                        // Prepare the config parameters needed by deployToBlueInstance
                        def deployConfig = [
                            albName: config.albName ?: 'blue-green-alb',                
                            blueTargetGroupName: config.blueTargetGroupName ?: 'blue-tg', 
                            blueTag: config.blueTag ?: 'Blue-Instance'              
                        ]
                        ec2Utils.deployToBlueInstance(deployConfig)
                    }
                }
            }


            stage('Test Environment') {
                when {
                    expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
                }
                steps {
                    script {
                        // Set ALB name dynamically or use a predefined one
                        config.albName = env.CUSTOM_ALB_NAME ?: 'blue-green-alb' 

                        ecsUtils.testEnvironment(config)
                    }
                }
            }
            
            stage('Manual Approval Before Switch Traffic ECS') {
                when { 
                    expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' } 
                }
                steps {
                    script {
                        echo """
                        🟡 Awaiting Manual Approval to Switch Traffic in ECS
                        ------------------------------------------------------
                        🔁 Rollback Version Tag : ${config.rollbackVersionTag}
                        🚀 New Image URI        : ${config.newImageUri}
                        📦 ECS Cluster          : ${config.ecsCluster}
                        🔵 Active Environment   : ${config.activeEnv}
                        🟢 Idle Environment     : ${config.idleEnv}
                        ⚙️  Idle Service         : ${config.idleService}
                        """

                        approvals.switchTrafficApprovalECS(config)
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
                        // Fetch ARNs dynamically
                        def blueTgArn = sh(script: "aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text", returnStdout: true).trim()
                        def greenTgArn = sh(script: "aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text", returnStdout: true).trim()
                        def albArn = sh(script: "aws elbv2 describe-load-balancers --names ${env.ALB_NAME} --query 'LoadBalancers[0].LoadBalancerArn' --output text", returnStdout: true).trim()
                        def listenerArn = sh(script: "aws elbv2 describe-listeners --load-balancer-arn ${albArn} --query 'Listeners[0].ListenerArn' --output text", returnStdout: true).trim()

                        // Decide which environment to switch to
                        def targetEnv = env.DEPLOY_TARGET_ENV ?: "GREEN" // Default to GREEN
                        def newTgArn = (targetEnv == "GREEN") ? greenTgArn : blueTgArn
                        def oldTgArn = (targetEnv == "GREEN") ? blueTgArn : greenTgArn

                        echo "🔄 Switching traffic to ${targetEnv}..."
                        echo "🔹 LISTENER_ARN: ${listenerArn}"
                        echo "🔹 NEW_TG_ARN : ${newTgArn}"
                        echo "🔹 OLD_TG_ARN : ${oldTgArn}"

                        switchTraffic([
                            LISTENER_ARN: listenerArn,
                            NEW_TG_ARN: newTgArn,
                            OLD_TG_ARN: oldTgArn,
                            NEW_ENV: targetEnv
                        ])
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
                            // Call shared library method with parameters
                            ec2Utils.tagSwapInstances([
                                blueTag : 'Blue-Instance',
                                greenTag: 'Green-Instance'
                            ])
                        } else if (config.implementation == 'ecs') {
                            // Pass ALB name or other minimal config needed by scaleDownOldEnvironment
                            ecsUtils.scaleDownOldEnvironment([
                                ALB_NAME: config.ALB_NAME ?: 'blue-green-alb'  // Replace or set in config
                            ])
                        }
                    }
                }
            }
        }
    }
}

