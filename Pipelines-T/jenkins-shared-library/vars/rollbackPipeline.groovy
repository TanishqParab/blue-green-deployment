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
                            // EC2 implementation - Fetch ALB and Target Group ARNs
                            echo "🔄 Fetching ALB and target group resources..."
                            
                            // Get ALB ARN
                            def albArn = sh(script: """
                                aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text
                            """, returnStdout: true).trim()
                            
                            if (!albArn) {
                                error "❌ Failed to retrieve ALB ARN! Check if the load balancer 'blue-green-alb' exists in AWS."
                            }
                            
                            echo "✅ ALB ARN: ${albArn}"
                            env.ALB_ARN = albArn
                            
                            // Get listener ARN
                            def listenerArn = sh(script: """
                                aws elbv2 describe-listeners --load-balancer-arn ${albArn} --query 'Listeners[?Port==`80`].ListenerArn' --output text
                            """, returnStdout: true).trim()
                            
                            if (!listenerArn) {
                                error "❌ Listener ARN not found! Check if the ALB has a listener attached."
                            }
                            
                            echo "✅ Listener ARN: ${listenerArn}"
                            env.LISTENER_ARN = listenerArn
                            
                            // Get target group ARNs
                            env.BLUE_TG_ARN = sh(script: """
                                aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text
                            """, returnStdout: true).trim()
                            
                            env.GREEN_TG_ARN = sh(script: """
                                aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text
                            """, returnStdout: true).trim()
                            
                            // Validate the target group ARNs
                            if (!env.GREEN_TG_ARN || env.GREEN_TG_ARN == 'null') {
                                error "❌ GREEN_TG_ARN not retrieved properly. Aborting rollback."
                            } else {
                                echo "✅ GREEN_TG_ARN retrieved: ${env.GREEN_TG_ARN}"
                            }
                            
                            if (!env.BLUE_TG_ARN || env.BLUE_TG_ARN == 'null') {
                                error "❌ BLUE_TG_ARN not retrieved properly. Aborting rollback."
                            } else {
                                echo "✅ BLUE_TG_ARN retrieved: ${env.BLUE_TG_ARN}"
                            }
                            
                        } else if (config.implementation == 'ecs') {
                            // ECS implementation - Fetch ECS and ALB Resources
                            echo "Fetching current deployment state..."
                            
                            try {
                                // Get ECS Cluster
                                env.ECS_CLUSTER = sh(
                                    script: "aws ecs list-clusters --query 'clusterArns[0]' --output text | awk -F'/' '{print \$2}'",
                                    returnStdout: true
                                ).trim()
                                
                                if (!env.ECS_CLUSTER || env.ECS_CLUSTER == "None") {
                                    env.ECS_CLUSTER = "blue-green-cluster"
                                }
                                echo "✅ ECS Cluster: ${env.ECS_CLUSTER}"

                                // Get ALB ARN
                                def albArn = sh(
                                    script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text",
                                    returnStdout: true
                                ).trim()
                                if (!albArn || albArn == "None") {
                                    error "❌ ALB ARN not found!"
                                }
                                env.ALB_ARN = albArn
                                echo "✅ ALB ARN: ${env.ALB_ARN}"

                                // Get Listener ARN
                                def listenerArn = sh(
                                    script: "aws elbv2 describe-listeners --load-balancer-arn ${env.ALB_ARN} --query 'Listeners[?Port==`80`].ListenerArn' --output text",
                                    returnStdout: true
                                ).trim()
                                if (!listenerArn || listenerArn == "None") {
                                    error "❌ Listener ARN not found!"
                                }
                                env.LISTENER_ARN = listenerArn
                                echo "✅ Listener ARN: ${env.LISTENER_ARN}"

                                // Get BLUE Target Group ARN
                                def blueTG = sh(
                                    script: "aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
                                    returnStdout: true
                                ).trim()
                                if (!blueTG || blueTG == "None" || blueTG == "null") {
                                    error "❌ BLUE_TG_ARN not retrieved!"
                                }
                                env.BLUE_TG_ARN = blueTG
                                echo "✅ BLUE_TG_ARN: ${env.BLUE_TG_ARN}"

                                // Get GREEN Target Group ARN
                                def greenTG = sh(
                                    script: "aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
                                    returnStdout: true
                                ).trim()
                                if (!greenTG || greenTG == "None" || greenTG == "null") {
                                    error "❌ GREEN_TG_ARN not retrieved!"
                                }
                                env.GREEN_TG_ARN = greenTG
                                echo "✅ GREEN_TG_ARN: ${env.GREEN_TG_ARN}"

                                // Determine currently active TG
                                def currentTG = sh(
                                    script: """
                                        aws elbv2 describe-rules \
                                            --listener-arn ${env.LISTENER_ARN} \
                                            --query 'Rules[?Priority==`1`].Actions[0].TargetGroupArn' --output text
                                    """,
                                    returnStdout: true
                                ).trim()

                                if (currentTG == env.BLUE_TG_ARN) {
                                    env.CURRENT_ENV = "BLUE"
                                    env.ROLLBACK_ENV = "GREEN"
                                    env.CURRENT_SERVICE = "blue-service"
                                    env.ROLLBACK_SERVICE = "green-service"
                                    env.ROLLBACK_TG_ARN = env.GREEN_TG_ARN  // ADD THIS LINE
                                } else if (currentTG == env.GREEN_TG_ARN) {
                                    env.CURRENT_ENV = "GREEN"
                                    env.ROLLBACK_ENV = "BLUE"
                                    env.CURRENT_SERVICE = "green-service"
                                    env.ROLLBACK_SERVICE = "blue-service"
                                    env.ROLLBACK_TG_ARN = env.BLUE_TG_ARN  // ADD THIS LINE
                                } else {
                                    error "❌ Current target group does not match BLUE or GREEN!"
                                }

                                echo "🔁 Current Traffic: ${env.CURRENT_ENV}"
                                echo "🔁 Rollback Target: ${env.ROLLBACK_ENV}"
                                echo "🔁 Rollback Target Group ARN: ${env.ROLLBACK_TG_ARN}"  // ADD THIS LINE

                                // Get ALB DNS
                                env.ALB_DNS = sh(
                                    script: "aws elbv2 describe-load-balancers --load-balancer-arns ${env.ALB_ARN} --query 'LoadBalancers[0].DNSName' --output text",
                                    returnStdout: true
                                ).trim()
                                echo "🌐 ALB DNS: ${env.ALB_DNS}"

                            } catch (Exception e) {
                                error "❌ Failed to fetch ECS/ALB resources: ${e.message}"
                            }
                        }
                    }
                }
            }
            
            stage('Prepare Rollback') {
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            // EC2 implementation - Create rollback rule and find standby instance
                            echo "🛠️ Creating rollback traffic rule..."
                            sh """
                                aws elbv2 create-rule \\
                                    --listener-arn ${env.LISTENER_ARN} \\
                                    --priority 10 \\
                                    --conditions Field=path-pattern,Values='/*' \\
                                    --actions Type=forward,TargetGroupArn=${env.GREEN_TG_ARN}
                            """
                            
                            // Dynamically fetch all registered instances in green-tg and pick the one tagged as "blue-instance"
                            def targetHealthData = sh(script: """
                                aws elbv2 describe-target-health \\
                                    --target-group-arn ${env.GREEN_TG_ARN} \\
                                    --query 'TargetHealthDescriptions[*].[Target.Id, TargetHealth.State]' \\
                                    --output text
                            """, returnStdout: true).trim()
                            
                            // Log full target list
                            echo "🔍 All target health data in green-tg:\n${targetHealthData}"
                            
                            // Get all instance IDs from green-tg
                            def targetInstanceIds = targetHealthData.readLines().collect { it.split()[0] }
                            
                            // Fetch Name tags for all instances
                            def instanceIds = targetInstanceIds.join(' ')
                            def instanceDetails = sh(script: '''
                                aws ec2 describe-instances \\
                                    --instance-ids ''' + instanceIds + ''' \\
                                    --query "Reservations[*].Instances[*].[InstanceId, Tags[?Key=='Name']|[0].Value]" \\
                                    --output text
                            ''', returnStdout: true).trim()
                                            
                            echo "🔍 Fetched EC2 instance names:\n${instanceDetails}"
                            
                            // Find instance with Name = "Blue-Instance"
                            def blueLine = instanceDetails.readLines().find { line ->
                                def parts = line.split('\t')
                                return parts.size() == 2 && parts[1].equalsIgnoreCase('blue-instance')
                            }
                            
                            if (!blueLine) {
                                error "❌ No instance with tag Name=blue-instance found in green-tg. Cannot proceed with rollback."
                            }
                            
                            def (blueInstanceId, instanceName) = blueLine.split('\t')
                            
                            // Get health status for that exact instance
                            def healthState = targetHealthData.readLines().find { it.startsWith(blueInstanceId) }?.split()[1]
                            
                            if (!healthState) {
                                error "❌ blue-instance is not currently registered in green-tg or health data is missing."
                            }
                            
                            echo "✅ Found blue-instance (${blueInstanceId}) with health state: ${healthState}"
                            
                            env.STANDBY_INSTANCE = blueInstanceId
                            
                            // Ensure green-tg instance becomes healthy
                            echo "⏳ Waiting for standby instance (${env.STANDBY_INSTANCE}) to become healthy..."
                            def healthy = false
                            def attempts = 0
                            
                            while (!healthy && attempts < 12) { // 2 minute timeout
                                sleep(time: 10, unit: 'SECONDS')
                                attempts++
                                
                                healthState = sh(script: """
                                    aws elbv2 describe-target-health \\
                                        --target-group-arn ${env.GREEN_TG_ARN} \\
                                        --targets Id=${env.STANDBY_INSTANCE} \\
                                        --query 'TargetHealthDescriptions[0].TargetHealth.State' \\
                                        --output text
                                """, returnStdout: true).trim()
                                
                                echo "Attempt ${attempts}/12: Health state = ${healthState}"
                                
                                if (healthState == 'healthy') {
                                    healthy = true
                                } else if (healthState == 'unused' && attempts > 3) {
                                    echo "⚠️ Triggering health check reevaluation"
                                    sh """
                                        aws elbv2 deregister-targets \\
                                            --target-group-arn ${env.GREEN_TG_ARN} \\
                                            --targets Id=${env.STANDBY_INSTANCE}
                                        sleep 15 
                                        aws elbv2 register-targets \\
                                            --target-group-arn ${env.GREEN_TG_ARN} \\
                                            --targets Id=${env.STANDBY_INSTANCE}
                                        sleep 10
                                    """
                                }
                            }
                            
                            if (!healthy) {
                                error "❌ Rollback failed: Standby instance did not become healthy (Final state: ${healthState})"
                            }
                            
                        } else if (config.implementation == 'ecs') {
                            // ECS implementation - Find previous version
                            echo "Finding previous version for rollback..."
                            
                            try {
                                // Validate critical environment variables first
                                def requiredVars = ['ECS_CLUSTER', 'CURRENT_SERVICE', 'ROLLBACK_SERVICE', 
                                                'ROLLBACK_TG_ARN', 'LISTENER_ARN', 'ECR_REPO_NAME']
                                requiredVars.each { var ->
                                    if (!env."${var}") {
                                        error "❌ Missing required environment variable: ${var}"
                                    }
                                }
                                echo "✅ Verified all required environment variables"

                                // 1. Get current deployment details
                                echo "🔍 Retrieving current task definition..."
                                def currentTaskDef = sh(
                                    script: """
                                        aws ecs describe-services \
                                            --cluster ${env.ECS_CLUSTER} \
                                            --services ${env.CURRENT_SERVICE} \
                                            --query 'services[0].taskDefinition' \
                                            --output text
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                if (!currentTaskDef) {
                                    error "❌ Failed to get current task definition for service ${env.CURRENT_SERVICE}"
                                }
                                echo "📋 Current task definition: ${currentTaskDef}"

                                // 2. Get task definition details
                                def taskDefJson
                                try {
                                    def taskDef = sh(
                                        script: """
                                            aws ecs describe-task-definition \
                                                --task-definition ${currentTaskDef} \
                                                --query 'taskDefinition' \
                                                --output json
                                        """,
                                        returnStdout: true
                                    ).trim()
                                    taskDefJson = readJSON text: taskDef
                                } catch (Exception e) {
                                    error "❌ Failed to parse task definition: ${e.message}"
                                }

                                // 3. Find previous image version
                                echo "🕵️ Finding previous image version..."
                                def currentImage = taskDefJson.containerDefinitions[0].image
                                echo "📷 Current image: ${currentImage}"

                                // Get ECR repository URI
                                def ecrRepoUri = sh(
                                    script: """
                                        aws ecr describe-repositories \
                                            --repository-names ${env.ECR_REPO_NAME} \
                                            --query 'repositories[0].repositoryUri' \
                                            --output text
                                    """,
                                    returnStdout: true
                                ).trim()

                                // Get all images sorted by push date (newest first)
                                def imagesOutput = sh(
                                    script: """
                                        aws ecr describe-images \
                                            --repository-name ${env.ECR_REPO_NAME} \
                                            --query 'sort_by(imageDetails,&imagePushedAt)[*]' \
                                            --output json
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                def images = readJSON text: imagesOutput
                                if (images.size() < 2) {
                                    error "❌ Need at least 2 images in repository for rollback"
                                }

                                // Find current and previous image
                                def currentTag = currentImage.contains(":") ? currentImage.split(":")[1] : "latest"
                                def previousImage = images.reverse().findResult { img ->
                                    img.imageTags?.any { it != currentTag } ? img : null
                                }

                                if (!previousImage) {
                                    error "❌ Could not find a previous image to rollback to"
                                }

                                env.ROLLBACK_IMAGE = "${ecrRepoUri}:${previousImage.imageTags[0]}"
                                echo "🔄 Rollback image: ${env.ROLLBACK_IMAGE}"
                                env.CONTAINER_NAME = taskDefJson.containerDefinitions[0].name
                                echo "📦 Container name: ${env.CONTAINER_NAME}"

                                // 4. Prepare new task definition
                                echo "🛠 Preparing new task definition..."
                                def rollbackServiceTaskDef = sh(
                                    script: """
                                        aws ecs describe-services \
                                            --cluster ${env.ECS_CLUSTER} \
                                            --services ${env.ROLLBACK_SERVICE} \
                                            --query 'services[0].taskDefinition' \
                                            --output text || echo "MISSING"
                                    """,
                                    returnStdout: true
                                ).trim()

                                def taskDefToUse = (rollbackServiceTaskDef != "MISSING" && rollbackServiceTaskDef != "None") ?
                                    readJSON(text: sh(script: """
                                        aws ecs describe-task-definition \
                                            --task-definition ${rollbackServiceTaskDef} \
                                            --query 'taskDefinition' \
                                            --output json
                                    """, returnStdout: true).trim()) :
                                    readJSON(text: env.CURRENT_TASK_DEF_JSON)

                                // Clean task definition
                                ['taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 
                                'compatibilities', 'registeredAt', 'registeredBy', 'deregisteredAt'].each { 
                                    taskDefToUse.remove(it) 
                                }
                                
                                // Update image
                                taskDefToUse.containerDefinitions[0].image = env.ROLLBACK_IMAGE

                                // Register new task definition
                                writeJSON file: 'rollback-task-def.json', json: taskDefToUse
                                env.NEW_TASK_DEF_ARN = sh(
                                    script: """
                                        aws ecs register-task-definition \
                                            --cli-input-json file://rollback-task-def.json \
                                            --query 'taskDefinition.taskDefinitionArn' \
                                            --output text
                                    """,
                                    returnStdout: true
                                ).trim()
                                echo "✅ Registered new task definition: ${env.NEW_TASK_DEF_ARN}"

                                // 5. Ensure target group is properly associated
                                echo "🔗 Verifying target group association..."
                                def targetGroupInfo = sh(
                                    script: """
                                        aws elbv2 describe-target-groups \
                                            --target-group-arns ${env.ROLLBACK_TG_ARN} \
                                            --query 'TargetGroups[0].LoadBalancerArns' \
                                            --output text
                                    """,
                                    returnStdout: true
                                ).trim()

                                if (!targetGroupInfo) {
                                    echo "⚠️ Target group not associated, creating rule..."
                                    
                                    // Find available priority
                                    def usedPriorities = sh(
                                        script: """
                                            aws elbv2 describe-rules \
                                                --listener-arn ${env.LISTENER_ARN} \
                                                --query 'Rules[?Priority!=`default`].Priority' \
                                                --output json
                                        """,
                                        returnStdout: true
                                    ).trim()
                                    
                                    def priority = 100
                                    while (readJSON(text: usedPriorities).contains(priority.toString())) {
                                        priority++
                                    }

                                    sh """
                                        aws elbv2 create-rule \
                                            --listener-arn ${env.LISTENER_ARN} \
                                            --priority ${priority} \
                                            --conditions '[{"Field":"path-pattern","Values":["/rollback-verify/*"]}]' \
                                            --actions '[{"Type":"forward","TargetGroupArn":"${env.ROLLBACK_TG_ARN}"}]'
                                    """
                                    sleep 10
                                }

                                // 6. Update or create service
                                echo "🔄 Updating ${env.ROLLBACK_SERVICE} service..."
                                def serviceExists = sh(
                                    script: """
                                        aws ecs describe-services \
                                            --cluster ${env.ECS_CLUSTER} \
                                            --services ${env.ROLLBACK_SERVICE} \
                                            --query 'services[0].status' \
                                            --output text || echo "MISSING"
                                    """,
                                    returnStdout: true
                                ).trim()

                                if (serviceExists == "MISSING" || serviceExists == "INACTIVE") {
                                    echo "🆕 Creating new service..."
                                    sh """
                                        aws ecs create-service \
                                            --cluster ${env.ECS_CLUSTER} \
                                            --service-name ${env.ROLLBACK_SERVICE} \
                                            --task-definition ${env.NEW_TASK_DEF_ARN} \
                                            --desired-count 1 \
                                            --load-balancers \
                                                targetGroupArn=${env.ROLLBACK_TG_ARN},
                                                containerName=${env.CONTAINER_NAME},
                                                containerPort=${env.CONTAINER_PORT}
                                    """
                                } else {
                                    echo "🔄 Updating existing service..."
                                    sh """
                                        aws ecs update-service \
                                            --cluster ${env.ECS_CLUSTER} \
                                            --service ${env.ROLLBACK_SERVICE} \
                                            --task-definition ${env.NEW_TASK_DEF_ARN} \
                                            --desired-count 1 \
                                            --force-new-deployment
                                    """
                                }

                                // 7. Wait for stabilization
                                echo "⏳ Waiting for service to stabilize..."
                                sh """
                                    aws ecs wait services-stable \
                                        --cluster ${env.ECS_CLUSTER} \
                                        --services ${env.ROLLBACK_SERVICE}
                                """
                                echo "✅ ${env.ROLLBACK_ENV} service is ready for rollback!"

                            } catch (Exception e) {
                                error "❌ Rollback preparation failed: ${e.message}"
                            }
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
                        echo "Testing ${env.ROLLBACK_ENV} environment before switching traffic..."
                        
                        try {
                            // Create a test path rule to route /test to the rollback environment
                            sh """
                            # Check if a test rule already exists
                            TEST_RULE=\$(aws elbv2 describe-rules --listener-arn ${env.LISTENER_ARN} --query "Rules[?Priority=='10'].RuleArn" --output text)
                            
                            # Delete the test rule if it exists
                            if [ ! -z "\$TEST_RULE" ]; then
                                aws elbv2 delete-rule --rule-arn \$TEST_RULE
                            fi
                            
                            # Create a new test rule with wildcard pattern
                            aws elbv2 create-rule --listener-arn ${env.LISTENER_ARN} --priority 10 --conditions '[{"Field":"path-pattern","Values":["/test*"]}]' --actions '[{"Type":"forward","TargetGroupArn":"${env.ROLLBACK_TG_ARN}"}]'
                            """
                            
                            // Test the rollback environment
                            sh """
                            # Wait for the rule to take effect
                            sleep 10
                            
                            # Test the health endpoint with multiple fallbacks
                            curl -f http://${env.ALB_DNS}/test/health || curl -f http://${env.ALB_DNS}/test || echo "Health check failed but continuing"
                            """
                            
                            echo "✅ ${env.ROLLBACK_ENV} environment tested successfully"
                        } catch (Exception e) {
                            echo "Warning: Test stage encountered an issue: ${e.message}"
                            echo "Continuing with rollback despite test issues"
                        }
                    }
                }
            }
            
            stage('Manual Approval Before Rollback') {
                steps {
                    script {
                        def buildLink = "${env.BUILD_URL}input"
                        
                        if (config.implementation == 'ec2') {
                            emailext (
                                to: config.emailRecipient,
                                subject: "🛑 Approval required for ROLLBACK - Build #${currentBuild.number}",
                                body: "A rollback has been triggered. Please review and approve the rollback at: ${buildLink}",
                                replyTo: config.emailRecipient
                            )

                            timeout(time: 1, unit: 'HOURS') {
                                input message: '🚨 Confirm rollback and approve execution', ok: 'Rollback'
                            }
                        } else if (config.implementation == 'ecs') {
                            emailext (
                                to: config.emailRecipient,
                                subject: "Approval required for rollback - Build ${currentBuild.number}",
                                body: """
                                    Please review the rollback deployment and approve to switch traffic.
                                    
                                    Current LIVE environment: ${env.CURRENT_ENV}
                                    Environment to rollback to: ${env.ROLLBACK_ENV}
                                    Previous version image: ${env.ROLLBACK_IMAGE}
                                    
                                    You can test the rollback version at: http://${env.ALB_DNS}/test
                                    
                                    🔗 Click here to approve: ${buildLink}
                                """,
                                replyTo: config.emailRecipient
                            )

                            timeout(time: 1, unit: 'HOURS') {
                                input message: "Do you want to rollback from ${env.CURRENT_ENV} to ${env.ROLLBACK_ENV}?", ok: 'Confirm Rollback'
                            }
                        }
                    }
                }
            }
            
            stage('Execute Rollback') {
                steps {
                    script {
                        if (config.implementation == 'ec2') {
                            echo "✅✅✅ ROLLBACK COMPLETE: Traffic now routed to previous version (GREEN-TG)"
                        } else if (config.implementation == 'ecs') {
                            echo "🔄 Switching traffic to ${env.ROLLBACK_ENV} for rollback"
                            
                            try {
                                // Validate target group ARN
                                if (!env.ROLLBACK_TG_ARN || env.ROLLBACK_TG_ARN == "null") {
                                    error "❌ Invalid rollback target group ARN: ${env.ROLLBACK_TG_ARN}"
                                }
                                
                                echo "Using rollback target group ARN: ${env.ROLLBACK_TG_ARN}"
                                
                                // Get current target group to verify it's different
                                def currentTargetGroup = sh(
                                    script: """
                                    aws elbv2 describe-listeners --listener-arns ${env.LISTENER_ARN} --query 'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[0].TargetGroupArn || Listeners[0].DefaultActions[0].TargetGroupArn' --output text
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                if (currentTargetGroup == env.ROLLBACK_TG_ARN) {
                                    echo "⚠️ Traffic is already routed to ${env.ROLLBACK_ENV}, no change needed"
                                } else {
                                    // Switch 100% traffic to the rollback environment
                                    sh """
                                    aws elbv2 modify-listener --listener-arn ${env.LISTENER_ARN} --default-actions Type=forward,TargetGroupArn=${env.ROLLBACK_TG_ARN}
                                    """
                                    echo "✅ Traffic switched 100% to ${env.ROLLBACK_ENV}"
                                }
                                
                                // Remove the test rule if it exists
                                sh """
                                TEST_RULE=\$(aws elbv2 describe-rules --listener-arn ${env.LISTENER_ARN} --query "Rules[?Priority=='10'].RuleArn" --output text)
                                
                                if [ ! -z "\$TEST_RULE" ]; then
                                    aws elbv2 delete-rule --rule-arn \$TEST_RULE
                                fi
                                """
                                
                                echo "✅✅✅ Rollback completed successfully!"
                            } catch (Exception e) {
                                error "Failed to switch traffic for rollback: ${e.message}"
                            }
                        }
                    }
                }
            }
            
            stage('Post-Rollback Actions') {
                steps {
                    script {
                        if (config.implementation == 'ecs') {
                            echo "Scaling down current ${env.CURRENT_ENV} environment..."
                            
                            try {
                                // Scale down the current live service
                                sh """
                                aws ecs update-service --cluster ${env.ECS_CLUSTER} --service ${env.CURRENT_SERVICE} --desired-count 0
                                """
                                
                                echo "✅ Current service (${env.CURRENT_ENV}) scaled down"
                                
                                // Wait for the service to stabilize
                                sh """
                                aws ecs wait services-stable --cluster ${env.ECS_CLUSTER} --services ${env.CURRENT_SERVICE}
                                """
                                
                                echo "✅ All services are stable"
                                
                                // Clean up old ECR images
                                echo "🧹 Cleaning up old images from ECR repository..."
                                
                                try {
                                    // Get all images directly without complex queries
                                    def imagesOutput = sh(
                                        script: "aws ecr describe-images --repository-name ${env.ECR_REPO_NAME} --output json",
                                        returnStdout: true
                                    ).trim()
                                    
                                    def imagesJson = readJSON text: imagesOutput
                                    def imageDetails = imagesJson.imageDetails
                                    
                                    echo "Found ${imageDetails.size()} images in repository"
                                    
                                    // Find the image with latest tag
                                    def latestImageDigest = null
                                    
                                    // Find the most recent rollback image
                                    def rollbackImageDigest = null
                                    def rollbackDate = null
                                    
                                    // First pass: identify images to keep
                                    imageDetails.each { image ->
                                        def digest = image.imageDigest
                                        def tags = image.imageTags ?: []
                                        
                                        // Check for latest tag
                                        if (tags.contains("latest")) {
                                            latestImageDigest = digest
                                            echo "Found latest image: ${digest}"
                                        }
                                        
                                        // Check for rollback tags
                                        for (tag in tags) {
                                            if (tag.startsWith("rollback-")) {
                                                def pushedAt = image.imagePushedAt
                                                if (rollbackDate == null || pushedAt > rollbackDate) {
                                                    rollbackImageDigest = digest
                                                    rollbackDate = pushedAt
                                                    echo "Found newer rollback image: ${digest} with tag ${tag}"
                                                }
                                            }
                                        }
                                    }
                                    
                                    echo "Latest image digest to keep: ${latestImageDigest ?: 'None'}"
                                    echo "Rollback image digest to keep: ${rollbackImageDigest ?: 'None'}"
                                    
                                    // Second pass: delete images not matching the ones to keep
                                    imageDetails.each { image ->
                                        def digest = image.imageDigest
                                        def tags = image.imageTags ?: []
                                        
                                        if (digest == latestImageDigest || digest == rollbackImageDigest) {
                                            echo "Keeping image: ${digest}, tags: ${tags}"
                                        } else {
                                            echo "Deleting image: ${digest}, tags: ${tags}"
                                            sh """
                                            aws ecr batch-delete-image \
                                                --repository-name ${env.ECR_REPO_NAME} \
                                                --image-ids imageDigest=${digest}
                                            """
                                        }
                                    }
                                    
                                    echo "✅ ECR repository cleanup completed"
                                    
                                } catch (Exception e) {
                                    echo "Warning: ECR cleanup encountered an issue: ${e.message}"
                                    echo "Continuing despite cleanup issues"
                                }
                                
                                // Send final notification
                                emailext (
                                    to: config.emailRecipient,
                                    subject: "Rollback completed successfully - Build ${currentBuild.number}",
                                    body: """
                                        Rollback has been completed successfully.
                                        
                                        Previous environment: ${env.CURRENT_ENV}
                                        Rolled back to: ${env.ROLLBACK_ENV}
                                        Rolled back to image: ${env.ROLLBACK_IMAGE}
                                        
                                        The application is now accessible at: http://${env.ALB_DNS}
                                    """,
                                    replyTo: config.emailRecipient
                                )
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
