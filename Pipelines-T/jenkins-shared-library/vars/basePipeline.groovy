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
                        echo "Initializing Terraform..."
                        dir("${config.tfWorkingDir}") {
                            sh "terraform init"
                        }
                    }
                }
            }

            stage('Terraform Plan') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        def tgExist = true
                        def blueTG = ""
                        def greenTG = ""

                        try {
                            blueTG = sh(
                                script: "aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --region ${config.awsRegion} --output text",
                                returnStdout: true
                            ).trim()
                            greenTG = sh(
                                script: "aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --region ${config.awsRegion} --output text",
                                returnStdout: true
                            ).trim()
                        } catch (Exception e) {
                            echo "⚠️ Could not fetch TG ARNs. Assuming first build. Skipping TG vars in plan."
                            tgExist = false
                        }

                        def planCommand = "terraform plan"
                        if (config.varFile) {
                            planCommand += " -var-file=${config.varFile}"
                        }
                        
                        if (tgExist) {
                            if (config.implementation == 'ecs') {
                                planCommand += " -var='pipeline.blue_target_group_arn=${blueTG}' -var='pipeline.green_target_group_arn=${greenTG}'"
                            } else {
                                planCommand += " -var='blue_target_group_arn=${blueTG}' -var='green_target_group_arn=${greenTG}'"
                            }
                        }
                        
                        planCommand += " -out=tfplan"

                        echo "Running Terraform Plan: ${planCommand}"
                        dir("${config.tfWorkingDir}") {
                            sh "${planCommand}"
                            archiveArtifacts artifacts: 'tfplan', onlyIfSuccessful: true
                        }
                    }
                }
            }

            stage('Manual Approval') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        dir("${config.tfWorkingDir}") {
                            // Generate and save the full Terraform plan to a file
                            def planCmd = 'terraform plan -no-color'
                            if (config.varFile) {
                                planCmd += " -var-file=${config.varFile}"
                            }
                            planCmd += " > tfplan.txt"
                            sh planCmd
                
                            // Read the full plan for logging purposes
                            def tfPlan = readFile('tfplan.txt')
                
                            // Archive the plan as an artifact for download
                            archiveArtifacts artifacts: 'tfplan.txt', fingerprint: true
                
                            // Log plan to console for visibility
                            echo "========== Terraform Plan Start =========="
                            echo tfPlan
                            echo "========== Terraform Plan End ============"
                
                            // Construct artifact download link
                            def planDownloadLink = "${env.BUILD_URL}artifact/tfplan.txt"
                
                            // Email for approval with download link
                            emailext (
                                to: config.emailRecipient,
                                subject: "Approval required for Terraform apply - Build ${currentBuild.number}",
                                body: """
                                    Hi,
                
                                    A Terraform apply requires your approval.
                
                                    👉 Review the Terraform plan here (download full plan):
                                    ${planDownloadLink}
                
                                    Once reviewed, please approve or abort the deployment at:
                                    ${env.BUILD_URL}input
                
                                    Regards,  
                                    Jenkins Automation
                                """,
                                replyTo: config.emailRecipient
                            )
                
                            // Input prompt for manual approval
                            timeout(time: 1, unit: 'HOURS') {
                                input(
                                    id: 'ApplyApproval',
                                    message: "Terraform Apply Approval Required",
                                    ok: "Apply",
                                    parameters: [],
                                    description: """⚠️ Full plan is too long for this screen.
                
                ✅ Check the full plan in:
                - [tfplan.txt Artifact](${planDownloadLink})
                - Console Output (above this stage)"""
                                )
                            }
                        }
                    }
                }
            }

            stage('Apply Infrastructure') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        echo "Running Terraform apply"
                        dir("${config.tfWorkingDir}") {
                            sh "terraform apply -auto-approve tfplan"
                            
                            // Save the state file
                            archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true 
                        }
                        
                        if (config.implementation == 'ec2') {
                            // EC2-specific post-apply steps
                            echo "Waiting for instances to start..."
                            sleep(60)  // Give time for instances to fully boot

                            echo "Checking instance states..."
                            sh """
                            aws ec2 describe-instances \\
                            --filters "Name=tag:Environment,Values=Blue-Green" \\
                            --query 'Reservations[*].Instances[*].[InstanceId, State.Name]' \\
                            --output table
                            """

                            echo "Retrieving instance IPs..."
                            def instances = sh(
                                script: """
                                aws ec2 describe-instances \\
                                --filters "Name=tag:Environment,Values=Blue-Green" "Name=instance-state-name,Values=running" \\
                                --query 'Reservations[*].Instances[*].PublicIpAddress' \\
                                --output text
                                """,
                                returnStdout: true
                            ).trim()

                            if (!instances) {
                                error "No running instances found! Check AWS console and tagging."
                            }

                            def instanceList = instances.split("\n")

                            instanceList.each { instance ->
                                echo "Deploying to instance: ${instance}"
                                sshagent([config.sshKeyId]) {
                                    sh """
                                    echo "Copying app.py and setup script to ${instance}..."
                                    scp -o StrictHostKeyChecking=no ${config.tfWorkingDir}/modules/ec2/scripts/${config.appFile} ec2-user@${instance}:/home/ec2-user/${config.appFile}
                                    scp -o StrictHostKeyChecking=no ${config.tfWorkingDir}/modules/ec2/scripts/setup_flask_service.py ec2-user@${instance}:/home/ec2-user/setup_flask_service.py

                                    echo "Running setup script on ${instance}..."
                                    ssh ec2-user@${instance} 'chmod +x /home/ec2-user/setup_flask_service.py && sudo python3 /home/ec2-user/setup_flask_service.py'
                                    """
                                }
                            }
                        } else if (config.implementation == 'ecs') {
                            // ECS-specific post-apply steps
                            echo "Waiting for ECS services to stabilize..."
                            sleep(60)  // Give time for services to start
                            
                            // Get the cluster name
                            def cluster = sh(
                                script: "terraform -chdir=${config.tfWorkingDir} output -raw ecs_cluster_id",
                                returnStdout: true
                            ).trim()
                            
                            // Check ECS service status
                            sh """
                            aws ecs describe-services --cluster ${cluster} --services blue-service --query 'services[0].{Status:status,DesiredCount:desiredCount,RunningCount:runningCount}' --output table
                            """
                            
                            // Get the ALB DNS name
                            def albDns = sh(
                                script: "terraform -chdir=${config.tfWorkingDir} output -raw alb_dns_name",
                                returnStdout: true
                            ).trim()
                            
                            echo "Application is accessible at: http://${albDns}"
                            
                            // Test the application
                            sh """
                            # Wait for the application to be fully available
                            sleep 30
                            
                            # Test the health endpoint
                            curl -f http://${albDns}/health || echo "Health check failed but continuing"
                            """
                        }
                    }
                }
            }

            
            stage('Register EC2 Instances to Target Groups') {
                when {
                    expression { config.implementation == 'ec2' }
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
                        // Final approval URL (no inputId needed in URL)
                        def destroyLink = "${env.BUILD_URL}input"
                
                        emailext (
                            to: config.emailRecipient,
                            subject: "🚨 Approval Required for Terraform Destroy - Build ${currentBuild.number}",
                            body: """
                            WARNING: You are about to destroy AWS infrastructure.
                
                            👉 Click the link below to approve destruction:
                
                            ${destroyLink}
                            """,
                            replyTo: config.emailRecipient
                        )
                
                        timeout(time: 1, unit: 'HOURS') {
                            input message: '⚠️ Confirm destruction of infrastructure?', ok: 'Destroy Now'
                        }
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
                        // Get the build number containing the state
                        def buildNumber = input(
                            message: "Enter the build number that created the infrastructure (e.g., 42)",
                            parameters: [string(name: 'BUILD_NUMBER')]
                        )
                
                        // Fetch the archived state file
                        dir("${config.tfWorkingDir}") {
                            copyArtifacts(
                                projectName: env.JOB_NAME,
                                selector: specific("${buildNumber}"),
                                filter: "terraform.tfstate",
                                target: "."
                            )
                        }
                
                        // Initialize and destroy
                        dir("${config.tfWorkingDir}") {
                            sh "terraform init"
                            def destroyCmd = "terraform destroy -auto-approve"
                            if (config.varFile) {
                                destroyCmd += " -var-file=${config.varFile}"
                            }
                            sh destroyCmd
                        }
                    }
                }
            }
        }
    }
}
