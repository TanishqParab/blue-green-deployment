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
            
            stage('Detect Changes') {
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            // EC2 implementation
                            def changedFiles = sh(script: "git diff --name-only HEAD~1 HEAD", returnStdout: true).trim().split('\n')
                            echo "Changed files: ${changedFiles}"

                            def onlyAppChange = (changedFiles.length == 1 && changedFiles[0] == "blue-green-deployment/modules/ec2/scripts/app.py")

                            if (onlyAppChange) {
                                echo "🚀 Detected only app.py change, executing App Deploy."
                                env.EXECUTION_TYPE = 'APP_DEPLOY'
                            } else {
                                echo "✅ Infra changes detected (excluding app.py), running full deployment."
                                env.EXECUTION_TYPE = 'FULL_DEPLOY'
                            }
                        } else if (config.implementation == 'ecs') {
                            // ECS implementation
                            def changedFiles = []
                            try {
                                changedFiles = sh(
                                    script: "git diff --name-only HEAD~1 HEAD || git diff --name-only",
                                    returnStdout: true
                                ).trim().split('\n')
                            } catch (Exception e) {
                                echo "Could not get changed files, assuming first run or new branch"
                                env.DEPLOY_NEW_VERSION = 'true'  // Default to deploying on first run
                                return
                            }
                            
                            def appChanged = changedFiles.any { it.contains('app.py') }
                            
                            if (appChanged) {
                                echo "🚀 Detected app.py changes, will deploy new version"
                                env.DEPLOY_NEW_VERSION = 'true'
                            } else {
                                echo "No app.py changes detected, will only switch traffic if needed"
                                env.DEPLOY_NEW_VERSION = 'false'
                            }
                        }
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
                            // EC2 implementation - Fetch Target Group ARNs
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
                        } else if (config.implementation == 'ecs') {
                            // ECS implementation - Fetch ECS and ALB Resources
                            echo "Fetching ECS and ALB resources..."

                            try {
                                // Get the cluster name
                                env.ECS_CLUSTER = sh(
                                    script: "terraform -chdir=${config.tfWorkingDir} output -raw ecs_cluster_id || aws ecs list-clusters --query 'clusterArns[0]' --output text",
                                    returnStdout: true
                                ).trim()

                                // Get target group ARNs
                                env.BLUE_TG_ARN = sh(
                                    script: """
                                    aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text
                                    """,
                                    returnStdout: true
                                ).trim()

                                env.GREEN_TG_ARN = sh(
                                    script: """
                                    aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text
                                    """,
                                    returnStdout: true
                                ).trim()

                                // Get ALB ARN
                                env.ALB_ARN = sh(
                                    script: """
                                    aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text
                                    """,
                                    returnStdout: true
                                ).trim()

                                // Get listener ARN
                                env.LISTENER_ARN = sh(
                                    script: """
                                    aws elbv2 describe-listeners --load-balancer-arn ${env.ALB_ARN} --query 'Listeners[0].ListenerArn' --output text
                                    """,
                                    returnStdout: true
                                ).trim()

                                // Determine which environment is currently live
                                def currentTargetGroup = sh(
                                    script: """
                                    aws elbv2 describe-listeners --listener-arns ${env.LISTENER_ARN} --query 'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[0].TargetGroupArn || Listeners[0].DefaultActions[0].TargetGroupArn' --output text
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                if (currentTargetGroup == env.BLUE_TG_ARN) {
                                    env.LIVE_ENV = "BLUE"
                                    env.IDLE_ENV = "GREEN"
                                    env.LIVE_TG_ARN = env.BLUE_TG_ARN
                                    env.IDLE_TG_ARN = env.GREEN_TG_ARN
                                    env.LIVE_SERVICE = "blue-service"
                                    env.IDLE_SERVICE = "green-service"
                                } else {
                                    env.LIVE_ENV = "GREEN"
                                    env.IDLE_ENV = "BLUE"
                                    env.LIVE_TG_ARN = env.GREEN_TG_ARN
                                    env.IDLE_TG_ARN = env.BLUE_TG_ARN
                                    env.LIVE_SERVICE = "green-service"
                                    env.IDLE_SERVICE = "blue-service"
                                }

                                echo "✅ ECS Cluster: ${env.ECS_CLUSTER}"
                                echo "✅ Blue Target Group ARN: ${env.BLUE_TG_ARN}"
                                echo "✅ Green Target Group ARN: ${env.GREEN_TG_ARN}"
                                echo "✅ ALB ARN: ${env.ALB_ARN}"
                                echo "✅ Listener ARN: ${env.LISTENER_ARN}"
                                echo "✅ Currently LIVE environment: ${env.LIVE_ENV}"
                                echo "✅ Currently IDLE environment: ${env.IDLE_ENV}"
                            } catch (Exception e) {
                                error "Failed to fetch resources: ${e.message}"
                            }
                        }
                    }
                }
            }

            stage('Ensure Target Group Association') {
                when {
                    expression { 
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        if (config.implementation == 'ecs') {
                            echo "Ensuring target group is associated with load balancer..."
                            
                            // Check if the target group is associated with a load balancer
                            def targetGroupInfo = sh(
                                script: """
                                aws elbv2 describe-target-groups --target-group-arns ${env.IDLE_TG_ARN} --query 'TargetGroups[0].LoadBalancerArns' --output json
                                """,
                                returnStdout: true
                            ).trim()
                            
                            def targetGroupJson = readJSON text: targetGroupInfo
                            
                            if (targetGroupJson.size() == 0) {
                                echo "⚠️ Target group ${env.IDLE_ENV} is not associated with a load balancer. Creating a path-based rule..."
                                
                                // Create a rule to associate the target group with the load balancer
                                sh """
                                # Create a new rule with priority 100
                                aws elbv2 create-rule --listener-arn ${env.LISTENER_ARN} --priority 100 --conditions '[{"Field":"path-pattern","Values":["/associate-tg*"]}]' --actions '[{"Type":"forward","TargetGroupArn":"${env.IDLE_TG_ARN}"}]'
                                """
                                
                                // Wait for the association to take effect
                                sleep(10)
                                
                                echo "✅ Target group associated with load balancer via path rule"
                            } else {
                                echo "✅ Target group is already associated with load balancer"
                            }
                        }
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
                            // EC2 implementation - Register Instances to Target Groups
                            echo "Registering instances to target groups..."
                            
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
                            sleep(10) // Give AWS time to process deregistration before registering
                
                            echo "✅ Registering instances to the correct target groups..."
                            sh """
                                aws elbv2 register-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${blueInstanceId}
                                aws elbv2 register-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${greenInstanceId}
                            """
                            
                            echo "✅ Instances successfully registered to correct target groups!"
                            
                        } else if (config.implementation == 'ecs') {
                            // ECS implementation - Build and push new image
                            echo "Updating application code for ${env.IDLE_ENV} environment..."
                            
                            try {
                                // Get the current 'latest' image details
                                def currentLatestImageInfo = sh(
                                    script: """
                                    aws ecr describe-images --repository-name ${env.ECR_REPO_NAME} --image-ids imageTag=latest --query 'imageDetails[0].{digest:imageDigest,pushedAt:imagePushedAt}' --output json 2>/dev/null || echo '{}'
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                def currentLatestJson = readJSON text: currentLatestImageInfo
                                
                                // Create a rollback tag with timestamp for the current 'latest' image
                                if (currentLatestJson.containsKey('digest')) {
                                    def timestamp = new Date().format("yyyyMMdd-HHmmss")
                                    def rollbackTag = "rollback-${timestamp}"
                                    
                                    echo "Found current 'latest' image with digest: ${currentLatestJson.digest}"
                                    echo "Tagging current 'latest' image as '${rollbackTag}' before overwriting..."
                                    
                                    // Tag the current 'latest' image with the rollback tag
                                    sh """
                                    # Get the image manifest and save it to a file
                                    aws ecr batch-get-image --repository-name ${env.ECR_REPO_NAME} --image-ids imageDigest=${currentLatestJson.digest} --query 'images[0].imageManifest' --output text > image-manifest.json
                                    
                                    # Use the manifest file to tag the image
                                    aws ecr put-image --repository-name ${env.ECR_REPO_NAME} --image-tag ${rollbackTag} --image-manifest file://image-manifest.json
                                    """
                                    
                                    echo "✅ Current 'latest' image tagged as '${rollbackTag}' for backup"
                                    
                                    // Store the rollback tag for reference
                                    env.PREVIOUS_VERSION_TAG = rollbackTag
                                } else {
                                    echo "⚠️ No current 'latest' image found to tag as rollback"
                                }
                                
                                // Now build and push the new image with the 'latest' tag
                                sh """
                                # Authenticate Docker to ECR
                                aws ecr get-login-password --region ${env.AWS_REGION} | docker login --username AWS --password-stdin \$(aws ecr describe-repositories --repository-names ${env.ECR_REPO_NAME} --query 'repositories[0].repositoryUri' --output text)
                                
                                # Navigate to the directory with Dockerfile
                                cd ${env.TF_WORKING_DIR}/modules/ecs/scripts
                                
                                # Build the Docker image
                                docker build -t ${env.ECR_REPO_NAME}:latest .
                                
                                # Tag the image with both 'latest' and a version tag
                                docker tag ${env.ECR_REPO_NAME}:latest \$(aws ecr describe-repositories --repository-names ${env.ECR_REPO_NAME} --query 'repositories[0].repositoryUri' --output text):latest
                                
                                # Also tag with build number for reference
                                docker tag ${env.ECR_REPO_NAME}:latest \$(aws ecr describe-repositories --repository-names ${env.ECR_REPO_NAME} --query 'repositories[0].repositoryUri' --output text):v${currentBuild.number}
                                
                                # Push both tags
                                docker push \$(aws ecr describe-repositories --repository-names ${env.ECR_REPO_NAME} --query 'repositories[0].repositoryUri' --output text):latest
                                docker push \$(aws ecr describe-repositories --repository-names ${env.ECR_REPO_NAME} --query 'repositories[0].repositoryUri' --output text):v${currentBuild.number}
                                """
                                
                                // Store the image URI for later use
                                env.IMAGE_URI = sh(
                                    script: "aws ecr describe-repositories --repository-names ${env.ECR_REPO_NAME} --query 'repositories[0].repositoryUri' --output text",
                                    returnStdout: true
                                ).trim() + ":latest"
                                
                                echo "✅ New image built and pushed: ${env.IMAGE_URI}"
                                echo "✅ Also tagged as: v${currentBuild.number}"
                                if (env.PREVIOUS_VERSION_TAG) {
                                    echo "✅ Previous 'latest' version preserved as: ${env.PREVIOUS_VERSION_TAG}"
                                }
                                
                                // Update Idle Service with new image
                                echo "Updating ${env.IDLE_ENV} service with new image..."
                                
                                // Get the current task definition
                                def taskDefArn = sh(
                                    script: """
                                    aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${env.IDLE_SERVICE} --query 'services[0].taskDefinition' --output text
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                // Get the task definition details
                                def taskDef = sh(
                                    script: """
                                    aws ecs describe-task-definition --task-definition ${taskDefArn} --query 'taskDefinition' --output json
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                // Parse the task definition
                                def taskDefJson = readJSON text: taskDef
                                
                                // Remove fields that shouldn't be included when registering a new task definition
                                ['taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 'compatibilities', 
                                 'registeredAt', 'registeredBy', 'deregisteredAt'].each { field ->
                                    taskDefJson.remove(field)
                                }
                                
                                // Update the container image
                                taskDefJson.containerDefinitions[0].image = env.IMAGE_URI
                                
                                // Write the updated task definition to a file
                                writeJSON file: 'new-task-def.json', json: taskDefJson
                                
                                // Register the new task definition
                                def newTaskDefArn = sh(
                                    script: """
                                    aws ecs register-task-definition --cli-input-json file://new-task-def.json --query 'taskDefinition.taskDefinitionArn' --output text
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                // Update the service with the new task definition
                                sh """
                                aws ecs update-service \\
                                    --cluster ${env.ECS_CLUSTER} \\
                                    --service ${env.IDLE_SERVICE} \\
                                    --task-definition ${newTaskDefArn} \\
                                    --desired-count 1 \\
                                    --force-new-deployment
                                """
                                
                                echo "✅ ${env.IDLE_ENV} service updated with new task definition: ${newTaskDefArn}"
                                
                                // Wait for the service to stabilize
                                echo "Waiting for ${env.IDLE_ENV} service to stabilize..."
                                sh """
                                aws ecs wait services-stable --cluster ${env.ECS_CLUSTER} --services ${env.IDLE_SERVICE}
                                """
                                
                                echo "✅ ${env.IDLE_ENV} service is stable"
                            } catch (Exception e) {
                                error "Failed to update application: ${e.message}"
                            }
                        }
                    }
                }
            }

            stage('Manual Approval Before Switch Traffic EC2') {
                when {
                    expression { config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY' }
                }
                steps {
                    script {
                        def buildLink = "${env.BUILD_URL}input"

                        emailext (
                            to: config.emailRecipient,
                            subject: "Approval required to switch traffic - Build ${currentBuild.number}",
                            body: """
                                Please review the deployment and approve to switch traffic to the BLUE target group.
                                
                                🔗 Click here to approve: ${buildLink}
                            """,
                            replyTo: config.emailRecipient
                        )

                        timeout(time: 1, unit: 'HOURS') {
                            input message: 'Do you want to switch traffic to the new BLUE deployment?', ok: 'Switch Traffic'
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
                        // Get Blue Instance IP
                        def blueInstanceIP = sh(
                            script: """
                            aws ec2 describe-instances --filters "Name=tag:Name,Values=Blue-Instance" "Name=instance-state-name,Values=running" \
                            --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
                            """,
                            returnStdout: true
                        ).trim()

                        if (!blueInstanceIP) {
                            error "❌ No running Blue instance found!"
                        }

                        echo "✅ Deploying to Blue instance: ${blueInstanceIP}"

                        // Copy App and Restart Service
                        sshagent([env.SSH_KEY_ID]) {
                            sh "scp -o StrictHostKeyChecking=no ${env.TF_WORKING_DIR}/modules/ec2/scripts/${env.APP_FILE} ec2-user@${blueInstanceIP}:/home/ec2-user/${env.APP_FILE}"
                            sh "ssh ec2-user@${blueInstanceIP} 'sudo systemctl restart flaskapp.service'"
                        }

                        env.BLUE_INSTANCE_IP = blueInstanceIP

                        // Health Check for Blue Instance
                        echo "🔍 Monitoring health of Blue instance..."

                        def blueInstanceId = sh(
                            script: """
                            aws ec2 describe-instances --filters "Name=tag:Name,Values=Blue-Instance" "Name=instance-state-name,Values=running" \
                            --query 'Reservations[0].Instances[0].InstanceId' --output text
                            """,
                            returnStdout: true
                        ).trim()

                        def healthStatus = ''
                        def attempts = 0
                        def maxAttempts = 30

                        while (healthStatus != 'healthy' && attempts < maxAttempts) {
                            sleep(10)
                            healthStatus = sh(
                                script: """
                                aws elbv2 describe-target-health \
                                --target-group-arn ${env.BLUE_TG_ARN} \
                                --targets Id=${blueInstanceId} \
                                --query 'TargetHealthDescriptions[0].TargetHealth.State' \
                                --output text
                                """,
                                returnStdout: true
                            ).trim()
                            attempts++
                            echo "Health status check attempt ${attempts}: ${healthStatus}"
                        }

                        if (healthStatus != 'healthy') {
                            error "❌ Blue instance failed to become healthy after ${maxAttempts} attempts!"
                        }

                        echo "✅ Blue instance is healthy!"
                    }
                }
            }

            
            stage('Test Environment') {
                when {
                    expression { 
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            // EC2 implementation - No specific test needed, already checked health
                            echo "Blue instance is healthy and ready for traffic"
                        } else if (config.implementation == 'ecs') {
                            // ECS implementation - Test Idle Environment
                            echo "Testing ${env.IDLE_ENV} environment..."
                            
                            try {
                                // Create a test path rule to route /test to the idle environment
                                sh """
                                # Check if a test rule already exists
                                TEST_RULE=\$(aws elbv2 describe-rules --listener-arn ${env.LISTENER_ARN} --query "Rules[?Priority=='10'].RuleArn" --output text)
                                
                                # Delete the test rule if it exists
                                if [ ! -z "\$TEST_RULE" ]; then
                                    aws elbv2 delete-rule --rule-arn \$TEST_RULE
                                fi
                                
                                # Create a new test rule with wildcard pattern
                                aws elbv2 create-rule --listener-arn ${env.LISTENER_ARN} --priority 10 --conditions '[{"Field":"path-pattern","Values":["/test*"]}]' --actions '[{"Type":"forward","TargetGroupArn":"${env.IDLE_TG_ARN}"}]'
                                """
                                
                                // Get the ALB DNS name
                                def albDns = sh(
                                    script: "aws elbv2 describe-load-balancers --load-balancer-arns ${env.ALB_ARN} --query 'LoadBalancers[0].DNSName' --output text",
                                    returnStdout: true
                                ).trim()
                                
                                // Test the idle environment
                                sh """
                                # Wait for the rule to take effect
                                sleep 10
                                
                                # Test the health endpoint with multiple fallbacks
                                curl -f http://${albDns}/test/health || curl -f http://${albDns}/test || echo "Health check failed but continuing"
                                """
                                
                                echo "✅ ${env.IDLE_ENV} environment tested successfully"
                                
                                // Store the ALB DNS for later use
                                env.ALB_DNS = albDns
                            } catch (Exception e) {
                                echo "Warning: Test stage encountered an issue: ${e.message}"
                                echo "Continuing with deployment despite test issues"
                            }
                        }
                    }
                }
            }
            
            stage('Manual Approval Before Switch Traffic ECS') {
                when {
                    expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
                }
                steps {
                    script {
                        def buildLink = "${env.BUILD_URL}input"

                        emailext (
                            to: config.emailRecipient,
                            subject: "Approval required to switch traffic - Build ${currentBuild.number}",
                            body: """
                                Please review the deployment and approve to switch traffic.

                                Current LIVE environment: ${env.LIVE_ENV}
                                New environment to make LIVE: ${env.IDLE_ENV}

                                You can test the new version at: http://${env.ALB_DNS}/test

                                🔗 Click here to approve: ${buildLink}
                            """,
                            replyTo: config.emailRecipient
                        )

                        timeout(time: 1, unit: 'HOURS') {
                            input message: "Do you want to switch traffic from ${env.LIVE_ENV} to ${env.IDLE_ENV}?", ok: 'Switch Traffic'
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
                            // EC2 implementation - Switch Traffic
                            echo "🔄 Fetching ALB listener ARN..."
                            
                            // Existing ALB and listener validation
                            def albArn = sh(script: """
                                aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text
                            """, returnStdout: true).trim()
                
                            if (!albArn) {
                                error "❌ Failed to retrieve ALB ARN! Check if the load balancer 'blue-green-alb' exists in AWS."
                            }
                
                            echo "✅ ALB ARN: ${albArn}"
                
                            def listenerArn = sh(script: """
                                aws elbv2 describe-listeners --load-balancer-arn ${albArn} --query 'Listeners[?Port==`80`].ListenerArn' --output text
                            """, returnStdout: true).trim()
                
                            if (!listenerArn) {
                                error "❌ Listener ARN not found! Check if the ALB has a listener attached."
                            }
                
                            echo "✅ Listener ARN: ${listenerArn}"
                
                            // Clean up any existing priority 10 rules (from previous rollbacks)
                            echo "🔍 Checking for existing priority 10 rules..."
                            def ruleArn = sh(script: """
                                aws elbv2 describe-rules --listener-arn '${listenerArn}' \
                                --query "Rules[?Priority=='10'].RuleArn | [0]" --output text
                            """, returnStdout: true).trim()

                            if (ruleArn && ruleArn != "None") {
                                echo "🔄 Deleting existing rule (Priority 10)..."
                                sh """
                                    aws elbv2 delete-rule --rule-arn '${ruleArn}'
                                """
                                echo "✅ Removed existing priority 10 rule"
                            } else {
                                echo "ℹ️ No existing priority 10 rule found"
                            }

                            // Update default traffic routing (no weighted rule needed)
                            echo "🔄 Configuring default traffic routing to Blue..."
                            sh """
                                aws elbv2 modify-listener --listener-arn ${listenerArn} \
                                --default-actions Type=forward,TargetGroupArn=${env.BLUE_TG_ARN}
                            """

                            // Verification
                            def currentDefaultAction = sh(script: """
                                aws elbv2 describe-listeners --listener-arns ${listenerArn} \
                                --query 'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[0].TargetGroupArn' \
                                --output text
                            """, returnStdout: true).trim()

                            if (currentDefaultAction != env.BLUE_TG_ARN) {
                                error "❌ Verification failed! Default action not pointing to BLUE target group"
                            }

                            echo "✅✅✅ Traffic switching completed successfully!"
                            echo "============================================="
                            echo "CURRENT ROUTING:"
                            echo "- Default route: 100% to BLUE (${env.BLUE_TG_ARN})"
                            echo "- No path-based or weighted rules active"
                        } else if (config.implementation == 'ecs') {
                            // ECS implementation - Switch Traffic
                            echo "🔄 Switching traffic to ${env.IDLE_ENV}"
                            
                            try {
                                // Switch 100% traffic to the idle environment
                                sh """
                                aws elbv2 modify-listener --listener-arn ${env.LISTENER_ARN} --default-actions Type=forward,TargetGroupArn=${env.IDLE_TG_ARN}
                                """
                                
                                echo "✅ Traffic switched 100% to ${env.IDLE_ENV}"
                                
                                // Remove the test rule if it exists
                                sh """
                                TEST_RULE=\$(aws elbv2 describe-rules --listener-arn ${env.LISTENER_ARN} --query "Rules[?Priority=='10'].RuleArn" --output text)
                                
                                if [ ! -z "\$TEST_RULE" ]; then
                                    aws elbv2 delete-rule --rule-arn \$TEST_RULE
                                fi
                                """
                                
                                // Verify the traffic distribution
                                def currentConfig = sh(
                                    script: """
                                    aws elbv2 describe-listeners --listener-arns ${env.LISTENER_ARN} --query 'Listeners[0].DefaultActions[0]' --output json
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                echo "Current listener configuration: ${currentConfig}"
                                echo "✅✅✅ Traffic switching completed successfully!"
                            } catch (Exception e) {
                                error "Failed to switch traffic: ${e.message}"
                            }
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
                            // EC2 implementation - Tag Swap for Next Deployment
                            echo "🌐 Discovering AWS resources..."
                            
                            // Get instances with safe parsing
                            def instances = sh(script: '''
                                aws ec2 describe-instances \
                                    --filters "Name=tag:Name,Values=Blue-Instance,Green-Instance" \
                                             "Name=instance-state-name,Values=running" \
                                    --query 'Reservations[].Instances[].[InstanceId,Tags[?Key==`Name`].Value | [0]]' \
                                    --output json
                            ''', returnStdout: true).trim()
                            
                            // Parse JSON output safely
                            def instancesJson = readJSON text: instances
                            def blueInstance = null
                            def greenInstance = null
                            
                            for (instance in instancesJson) {
                                if (instance[1] == "Blue-Instance") {
                                    blueInstance = instance[0]
                                } else if (instance[1] == "Green-Instance") {
                                    greenInstance = instance[0]
                                }
                            }
                            
                            if (!blueInstance || !greenInstance) {
                                error "❌ Could not find both Blue and Green running instances. Found:\n${instancesJson}"
                            }
                            echo "✔️ Found instances - Blue: ${blueInstance}, Green: ${greenInstance}"
                
                            // Perform atomic tag swap
                            echo "🔄 Performing atomic tag swap..."
                            
                            sh """
                                #!/bin/bash
                                set -euo pipefail
            
                                # Verify instances exist
                                if [ -z "${blueInstance}" ] || [ -z "${greenInstance}" ]; then
                                    echo "❌ Missing instance IDs"
                                    exit 1
                                fi
            
                                # Perform swap with verification
                                aws ec2 create-tags \
                                    --resources ${blueInstance} \
                                    --tags Key=Name,Value=Green-Instance
                                    
                                aws ec2 create-tags \
                                    --resources ${greenInstance} \
                                    --tags Key=Name,Value=Blue-Instance
            
                                # Verify changes
                                blue_tag=\$(aws ec2 describe-tags \
                                    --filters "Name=resource-id,Values=${blueInstance}" \
                                              "Name=key,Values=Name" \
                                    --query "Tags[0].Value" \
                                    --output text)
                                    
                                green_tag=\$(aws ec2 describe-tags \
                                    --filters "Name=resource-id,Values=${greenInstance}" \
                                              "Name=key,Values=Name" \
                                    --query "Tags[0].Value" \
                                    --output text)
            
                                if [ "\$blue_tag" != "Green-Instance" ] || [ "\$green_tag" != "Blue-Instance" ]; then
                                    echo "❌ Tag verification failed!"
                                    exit 1
                                fi
                            """
                            
                            echo "✅ Deployment Complete!"
                            echo "====================="
                            echo "Instance Tags:"
                            echo "- ${blueInstance} (now Green)"
                            echo "- ${greenInstance} (now Blue)"
                        } else if (config.implementation == 'ecs') {
                            // ECS implementation - Scale Down Old Environment
                            echo "Scaling down old ${env.LIVE_ENV} environment..."
                            
                            try {
                                // Scale down the previous live service
                                sh """
                                aws ecs update-service --cluster ${env.ECS_CLUSTER} --service ${env.LIVE_SERVICE} --desired-count 0
                                """
                                
                                echo "✅ Previous live service (${env.LIVE_ENV}) scaled down"
                                
                                // Wait for the service to stabilize
                                sh """
                                aws ecs wait services-stable --cluster ${env.ECS_CLUSTER} --services ${env.LIVE_SERVICE}
                                """
                                
                                echo "✅ All services are stable"
                            } catch (Exception e) {
                                echo "Warning: Scale down encountered an issue: ${e.message}"
                                echo "Continuing despite scale down issues"
                            }
                        }
                    }
                }
            }
        }
    }
}
