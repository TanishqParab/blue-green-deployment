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
                        echo "Fetching Target Group ARNs from AWS..."

                        env.BLUE_TG_ARN = sh(
                            script: """
                            aws elbv2 describe-target-groups --names "blue-tg" --query 'TargetGroups[0].TargetGroupArn' --output text
                            """,
                            returnStdout: true
                        ).trim()

                        env.GREEN_TG_ARN = sh(
                            script: """
                            aws elbv2 describe-target-groups --names "green-tg" --query 'TargetGroups[0].TargetGroupArn' --output text
                            """,
                            returnStdout: true
                        ).trim()

                        if (!env.BLUE_TG_ARN || !env.GREEN_TG_ARN) {
                            error "❌ Failed to fetch Target Group ARNs! Check if they exist in AWS."
                        }

                        echo "✅ Blue Target Group ARN: ${env.BLUE_TG_ARN}"
                        echo "✅ Green Target Group ARN: ${env.GREEN_TG_ARN}"

                        echo "Fetching EC2 instance IDs..."

                        def blueInstanceId = sh(
                            script: """
                            aws ec2 describe-instances --filters "Name=tag:Name,Values=Blue-Instance" "Name=instance-state-name,Values=running" \
                            --query 'Reservations[0].Instances[0].InstanceId' --output text
                            """,
                            returnStdout: true
                        ).trim()

                        def greenInstanceId = sh(
                            script: """
                            aws ec2 describe-instances --filters "Name=tag:Name,Values=Green-Instance" "Name=instance-state-name,Values=running" \
                            --query 'Reservations[0].Instances[0].InstanceId' --output text
                            """,
                            returnStdout: true
                        ).trim()

                        if (!blueInstanceId || !greenInstanceId) {
                            error "❌ Blue or Green instance not found! Check AWS console."
                        }

                        echo "✅ Blue Instance ID: ${blueInstanceId}"
                        echo "✅ Green Instance ID: ${greenInstanceId}"

                        echo "❌ Deregistering old instances before re-registering..."
                        sh """
                            aws elbv2 deregister-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${greenInstanceId}
                            aws elbv2 deregister-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${blueInstanceId}
                        """
                        sleep(10) // Allow time for deregistration

                        echo "✅ Registering instances to the correct target groups..."
                        sh """
                            aws elbv2 register-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${blueInstanceId}
                            aws elbv2 register-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${greenInstanceId}
                        """

                        echo "✅ EC2 instances successfully registered to correct target groups!"
                    }
                }
            }

            stage('Manual Approval for Destroy') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' }
                }
                steps {
                    script {
                        manualDestroyApproval(config)
                    }
                }
            }
            
            stage('Clean Resources') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' && config.implementation == 'ecs' }
                }
                steps {
                    script {
                        echo "🧹 Cleaning up ECR repository before destruction..."
                        
                        try {
                            // Check if the ECR repository exists
                            def ecrRepoExists = sh(
                                script: "aws ecr describe-repositories --repository-names ${config.ecrRepoName} --region ${config.awsRegion} &>/dev/null && echo 0 || echo 1",
                                returnStdout: true
                            ).trim() == "0"
                
                            if (ecrRepoExists) {
                                echo "🔍 Fetching all images in repository ${config.ecrRepoName}..."
                                
                                // Get all images directly without complex queries
                                def imagesOutput = sh(
                                    script: "aws ecr describe-images --repository-name ${config.ecrRepoName} --output json",
                                    returnStdout: true
                                ).trim()
                                
                                def imagesJson = readJSON text: imagesOutput
                                def imageDetails = imagesJson.imageDetails
                                
                                echo "Found ${imageDetails.size()} images in repository"
                                
                                // Delete all images
                                imageDetails.each { image ->
                                    def digest = image.imageDigest
                                    echo "Deleting image: ${digest}"
                                    sh """
                                    aws ecr batch-delete-image \\
                                        --repository-name ${config.ecrRepoName} \\
                                        --image-ids imageDigest=${digest}
                                    """
                                }
                            } else {
                                echo "ℹ️ ECR repository ${config.ecrRepoName} not found, skipping cleanup"
                            }
                        } catch (Exception e) {
                            echo "Warning: ECR cleanup encountered an issue: ${e.message}"
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
