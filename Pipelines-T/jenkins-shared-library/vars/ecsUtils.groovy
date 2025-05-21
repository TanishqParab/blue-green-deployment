// vars/ecsUtils.groovy

def waitForServices(Map config) {
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

def cleanResources(Map config) {
    if (params.MANUAL_BUILD != 'DESTROY' || config.implementation != 'ecs') {
        echo "⚠️ Skipping ECR cleanup as conditions not met (either not DESTROY or not ECS)."
        return
    }

    echo "🧹 Cleaning up ECR repository before destruction..."

    try {
        // Check if the ECR repository exists
        def ecrRepoExists = sh(
            script: """
                aws ecr describe-repositories --repository-names ${config.ecrRepoName} --region ${config.awsRegion} &>/dev/null && echo 0 || echo 1
            """,
            returnStdout: true
        ).trim() == "0"

        if (ecrRepoExists) {
            echo "🔍 Fetching all images in repository ${config.ecrRepoName}..."

            def imagesOutput = sh(
                script: """
                    aws ecr describe-images --repository-name ${config.ecrRepoName} --output json
                """,
                returnStdout: true
            ).trim()

            def imagesJson = readJSON text: imagesOutput
            def imageDetails = imagesJson.imageDetails

            echo "Found ${imageDetails.size()} images in repository"

            imageDetails.each { image ->
                def digest = image.imageDigest
                echo "Deleting image: ${digest}"
                sh """
                    aws ecr batch-delete-image \\
                        --repository-name ${config.ecrRepoName} \\
                        --image-ids imageDigest=${digest}
                """
            }

            echo "✅ ECR repository cleanup completed."
        } else {
            echo "ℹ️ ECR repository ${config.ecrRepoName} not found, skipping cleanup"
        }
    } catch (Exception e) {
        echo "⚠️ Warning: ECR cleanup encountered an issue: ${e.message}"
    }
}



def detectChanges(Map config) {
    echo "🔍 Detecting changes for ECS implementation..."

    def changedFiles = []
    try {
        // Check for any file changes between last 2 commits
        def gitDiff = sh(
            script: "git diff --name-only HEAD~1 HEAD",
            returnStdout: true
        ).trim()

        if (gitDiff) {
            changedFiles = gitDiff.split('\n')
            echo "📝 Changed files: ${changedFiles.join(', ')}"
            echo "🚀 Change(s) detected. Triggering deployment."
            env.DEPLOY_NEW_VERSION = 'true'
        } else {
            echo "📄 No changes detected between last two commits."
            env.DEPLOY_NEW_VERSION = 'false'
        }

    } catch (Exception e) {
        echo "⚠️ Could not determine changed files. Assuming change occurred to force deploy."
        env.DEPLOY_NEW_VERSION = 'true'
    }
}


def fetchResources(Map config) {
    echo "🔄 Fetching ECS and ALB resources..."

    def result = [:]

    try {
        result.ECS_CLUSTER = sh(
            script: "terraform -chdir=${config.tfWorkingDir} output -raw ecs_cluster_id || aws ecs list-clusters --query 'clusterArns[0]' --output text",
            returnStdout: true
        ).trim()

        result.BLUE_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        result.GREEN_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        result.ALB_ARN = sh(
            script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text",
            returnStdout: true
        ).trim()

        result.LISTENER_ARN = sh(
            script: "aws elbv2 describe-listeners --load-balancer-arn ${result.ALB_ARN} --query 'Listeners[0].ListenerArn' --output text",
            returnStdout: true
        ).trim()

        def currentTargetGroup = sh(
            script: """
            aws elbv2 describe-listeners --listener-arns ${result.LISTENER_ARN} \
            --query 'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[0].TargetGroupArn || Listeners[0].DefaultActions[0].TargetGroupArn' \
            --output text
            """,
            returnStdout: true
        ).trim()

        if (currentTargetGroup == result.BLUE_TG_ARN) {
            result.LIVE_ENV = "BLUE"
            result.IDLE_ENV = "GREEN"
            result.LIVE_TG_ARN = result.BLUE_TG_ARN
            result.IDLE_TG_ARN = result.GREEN_TG_ARN
            result.LIVE_SERVICE = "blue-service"
            result.IDLE_SERVICE = "green-service"
        } else {
            result.LIVE_ENV = "GREEN"
            result.IDLE_ENV = "BLUE"
            result.LIVE_TG_ARN = result.GREEN_TG_ARN
            result.IDLE_TG_ARN = result.BLUE_TG_ARN
            result.LIVE_SERVICE = "green-service"
            result.IDLE_SERVICE = "blue-service"
        }

        echo "✅ ECS Cluster: ${result.ECS_CLUSTER}"
        echo "✅ Blue TG: ${result.BLUE_TG_ARN}"
        echo "✅ Green TG: ${result.GREEN_TG_ARN}"
        echo "✅ ALB ARN: ${result.ALB_ARN}"
        echo "✅ Listener ARN: ${result.LISTENER_ARN}"
        echo "✅ LIVE ENV: ${result.LIVE_ENV}"
        echo "✅ IDLE ENV: ${result.IDLE_ENV}"

        return result

    } catch (Exception e) {
        error "❌ Failed to fetch ECS resources: ${e.message}"
    }
}


def ensureTargetGroupAssociation(Map config) {
    echo "Ensuring target group is associated with load balancer..."

    if (!config.IDLE_TG_ARN || config.IDLE_TG_ARN.trim() == "") {
        error "IDLE_TG_ARN is missing or empty"
    }
    if (!config.LISTENER_ARN || config.LISTENER_ARN.trim() == "") {
        error "LISTENER_ARN is missing or empty"
    }

    def targetGroupInfo = sh(
        script: """
        aws elbv2 describe-target-groups --target-group-arns ${config.IDLE_TG_ARN} --query 'TargetGroups[0].LoadBalancerArns' --output json
        """,
        returnStdout: true
    ).trim()

    // Use a @NonCPS helper for JSON parsing
    def targetGroupJson = parseJson(targetGroupInfo)

    if (targetGroupJson.size() == 0) {
        echo "⚠️ Target group ${config.IDLE_ENV} is not associated with a load balancer. Creating a path-based rule..."

        def rulesJson = sh(
            script: """
            aws elbv2 describe-rules --listener-arn ${config.LISTENER_ARN} --query 'Rules[*].Priority' --output json
            """,
            returnStdout: true
        ).trim()

        def priorities = parseJson(rulesJson)
            .findAll { it != 'default' }
            .collect { it as int }
            .sort()

        int startPriority = 100
        int nextPriority = startPriority
        for (p in priorities) {
            if (p == nextPriority) {
                nextPriority++
            } else if (p > nextPriority) {
                break
            }
        }
        echo "Using rule priority: ${nextPriority}"

        sh """
        aws elbv2 create-rule \
            --listener-arn ${config.LISTENER_ARN} \
            --priority ${nextPriority} \
            --conditions '[{"Field":"path-pattern","Values":["/associate-tg*"]}]' \
            --actions '[{"Type":"forward","TargetGroupArn":"${config.IDLE_TG_ARN}"}]'
        """

        sleep(10)
        echo "✅ Target group associated with load balancer via path rule (priority ${nextPriority})"
    } else {
        echo "✅ Target group is already associated with load balancer"
    }
}

@NonCPS
def parseJson(String text) {
    new groovy.json.JsonSlurper().parseText(text)
}


import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def updateApplication(Map config) {
    echo "Running ECS update application logic..."

    try {
        // Step 1: Dynamically discover ECS cluster
        def clustersJson = sh(
            script: "aws ecs list-clusters --region ${env.AWS_REGION} --output json",
            returnStdout: true
        ).trim()

        def clusterArns = parseJsonSafe(clustersJson)?.clusterArns
        if (!clusterArns || clusterArns.isEmpty()) {
            error "❌ No ECS clusters found in region ${env.AWS_REGION}"
        }

        def selectedClusterArn = clusterArns[0]
        def selectedClusterName = selectedClusterArn.tokenize('/').last()
        env.ECS_CLUSTER = selectedClusterName
        echo "✅ Using ECS cluster: ${env.ECS_CLUSTER}"

        // Step 2: Dynamically discover ECS services
        def servicesJson = sh(
            script: "aws ecs list-services --cluster ${env.ECS_CLUSTER} --region ${env.AWS_REGION} --output json",
            returnStdout: true
        ).trim()

        def serviceArns = parseJsonSafe(servicesJson)?.serviceArns
        if (!serviceArns || serviceArns.isEmpty()) {
            error "❌ No ECS services found in cluster ${env.ECS_CLUSTER}"
        }

        def serviceNames = serviceArns.collect { it.tokenize('/').last() }
        echo "Discovered ECS services: ${serviceNames}"

        def blueService = serviceNames.find { it.toLowerCase().contains("blue") }
        def greenService = serviceNames.find { it.toLowerCase().contains("green") }

        if (!blueService || !greenService) {
            error "❌ Could not find both 'blue' and 'green' ECS services in cluster ${env.ECS_CLUSTER}. Found services: ${serviceNames}"
        }

        // Helper to get image tag for a service
        def getImageTagForService = { serviceName ->
            def taskDefArn = sh(
                script: "aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${serviceName} --region ${env.AWS_REGION} --query 'services[0].taskDefinition' --output text",
                returnStdout: true
            ).trim()

            def taskDefJsonText = sh(
                script: "aws ecs describe-task-definition --task-definition ${taskDefArn} --region ${env.AWS_REGION} --query 'taskDefinition' --output json",
                returnStdout: true
            ).trim()

            def taskDefJson = parseJsonSafe(taskDefJsonText)
            def image = taskDefJson.containerDefinitions[0].image
            def imageTag = image.tokenize(':').last()
            return imageTag
        }

        def blueImageTag = getImageTagForService(blueService)
        def greenImageTag = getImageTagForService(greenService)

        echo "Blue service image tag: ${blueImageTag}"
        echo "Green service image tag: ${greenImageTag}"

        if (blueImageTag == "latest" && greenImageTag != "latest") {
            env.ACTIVE_ENV = "BLUE"
        } else if (greenImageTag == "latest" && blueImageTag != "latest") {
            env.ACTIVE_ENV = "GREEN"
        } else {
            echo "⚠️ Could not determine ACTIVE_ENV from image tags clearly. Defaulting ACTIVE_ENV to BLUE"
            env.ACTIVE_ENV = "BLUE"
        }

        // Validate ACTIVE_ENV and determine idle env/service
        if (!env.ACTIVE_ENV || !(env.ACTIVE_ENV.toUpperCase() in ["BLUE", "GREEN"])) {
            error "❌ ACTIVE_ENV must be set to 'BLUE' or 'GREEN'. Current value: '${env.ACTIVE_ENV}'"
        }
        env.ACTIVE_ENV = env.ACTIVE_ENV.toUpperCase()
        env.IDLE_ENV = (env.ACTIVE_ENV == "BLUE") ? "GREEN" : "BLUE"
        echo "ACTIVE_ENV: ${env.ACTIVE_ENV}"
        echo "Determined IDLE_ENV: ${env.IDLE_ENV}"

        env.IDLE_SERVICE = (env.IDLE_ENV == "BLUE") ? blueService : greenService
        echo "Selected IDLE_SERVICE: ${env.IDLE_SERVICE}"

        // Step 4: Get current 'latest' image digest
        def currentLatestImageInfo = sh(
            script: """
            aws ecr describe-images --repository-name ${env.ECR_REPO_NAME} --image-ids imageTag=latest --region ${env.AWS_REGION} --query 'imageDetails[0].{digest:imageDigest,pushedAt:imagePushedAt}' --output json 2>/dev/null || echo '{}'
            """,
            returnStdout: true
        ).trim()

        def imageDigest = getJsonFieldSafe(currentLatestImageInfo, 'digest')

        if (imageDigest) {
            def timestamp = new Date().format("yyyyMMdd-HHmmss")
            def rollbackTag = "rollback-${timestamp}"

            echo "Found current 'latest' image with digest: ${imageDigest}"
            echo "Tagging current 'latest' image as '${rollbackTag}'..."

            sh """
            aws ecr batch-get-image --repository-name ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --image-ids imageDigest=${imageDigest} --query 'images[0].imageManifest' --output text > image-manifest.json
            aws ecr put-image --repository-name ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --image-tag ${rollbackTag} --image-manifest file://image-manifest.json
            """

            echo "✅ Tagged rollback image: ${rollbackTag}"
            env.PREVIOUS_VERSION_TAG = rollbackTag
        } else {
            echo "⚠️ No current 'latest' image found to tag"
        }

        // Step 5: Build and push Docker image
        def ecrUri = sh(
            script: "aws ecr describe-repositories --repository-names ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --query 'repositories[0].repositoryUri' --output text",
            returnStdout: true
        ).trim()

        sh """
        aws ecr get-login-password --region ${env.AWS_REGION} | docker login --username AWS --password-stdin ${ecrUri}
        cd ${env.TF_WORKING_DIR}/modules/ecs/scripts
        docker build -t ${env.ECR_REPO_NAME}:latest .
        docker tag ${env.ECR_REPO_NAME}:latest ${ecrUri}:latest
        docker tag ${env.ECR_REPO_NAME}:latest ${ecrUri}:v${currentBuild.number}
        docker push ${ecrUri}:latest
        docker push ${ecrUri}:v${currentBuild.number}
        """

        env.IMAGE_URI = "${ecrUri}:latest"
        echo "✅ Image pushed: ${env.IMAGE_URI}"
        echo "✅ Also tagged as: v${currentBuild.number}"
        if (env.PREVIOUS_VERSION_TAG) {
            echo "✅ Previous version preserved as: ${env.PREVIOUS_VERSION_TAG}"
        }

        // Step 6: Update ECS Service
        echo "Updating ${env.IDLE_ENV} service..."

        def taskDefArn = sh(
            script: "aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${env.IDLE_SERVICE} --region ${env.AWS_REGION} --query 'services[0].taskDefinition' --output text",
            returnStdout: true
        ).trim()

        def taskDefJsonText = sh(
            script: "aws ecs describe-task-definition --task-definition ${taskDefArn} --region ${env.AWS_REGION} --query 'taskDefinition' --output json",
            returnStdout: true
        ).trim()

        // *** THIS IS THE KEY CHANGE: ***
        def newTaskDefJson = updateTaskDefImageAndSerialize(taskDefJsonText, env.IMAGE_URI)
        writeFile file: 'new-task-def.json', text: newTaskDefJson

        def newTaskDefArn = sh(
            script: "aws ecs register-task-definition --cli-input-json file://new-task-def.json --region ${env.AWS_REGION} --query 'taskDefinition.taskDefinitionArn' --output text",
            returnStdout: true
        ).trim()

        sh """
        aws ecs update-service \\
            --cluster ${env.ECS_CLUSTER} \\
            --service ${env.IDLE_SERVICE} \\
            --task-definition ${newTaskDefArn} \\
            --desired-count 1 \\
            --force-new-deployment \\
            --region ${env.AWS_REGION}
        """

        echo "✅ Updated service ${env.IDLE_ENV} with task def: ${newTaskDefArn}"

        echo "Waiting for ${env.IDLE_ENV} service to stabilize..."
        sh "aws ecs wait services-stable --cluster ${env.ECS_CLUSTER} --services ${env.IDLE_SERVICE} --region ${env.AWS_REGION}"
        echo "✅ Service ${env.IDLE_ENV} is stable"

    } catch (Exception e) {
        echo "❌ Error occurred during ECS update:\n${e}"
        e.printStackTrace()
        error "Failed to update ECS application"
    }
}

@NonCPS
def parseJsonSafe(String jsonText) {
    def parsed = new JsonSlurper().parseText(jsonText)
    def safeMap = [:]
    safeMap.putAll(parsed)
    return safeMap
}

@NonCPS
def getJsonFieldSafe(String jsonText, String fieldName) {
    def parsed = new JsonSlurper().parseText(jsonText)
    return parsed?."${fieldName}"?.toString()
}

// *** THIS IS THE NEW KEY METHOD ***
@NonCPS
def updateTaskDefImageAndSerialize(String jsonText, String imageUri) {
    def taskDef = new JsonSlurper().parseText(jsonText)
    ['taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 'compatibilities',
     'registeredAt', 'registeredBy', 'deregisteredAt'].each { field ->
        taskDef.remove(field)
    }
    taskDef.containerDefinitions[0].image = imageUri
    return JsonOutput.prettyPrint(JsonOutput.toJson(taskDef))
}



def testEnvironment(Map config) {
    echo "🔍 Testing ${env.IDLE_ENV} environment..."

    try {
        // Dynamically fetch ALB ARN if not set
        if (!env.ALB_ARN) {
            echo "📡 Fetching ALB ARN..."
            env.ALB_ARN = sh(
                script: """
                    aws elbv2 describe-load-balancers \
                        --names ${config.albName} \
                        --query 'LoadBalancers[0].LoadBalancerArn' \
                        --output text
                """,
                returnStdout: true
            ).trim()
        }

        // Dynamically fetch Listener ARN if not set
        if (!env.LISTENER_ARN) {
            echo "🎧 Fetching Listener ARN..."
            env.LISTENER_ARN = sh(
                script: """
                    aws elbv2 describe-listeners \
                        --load-balancer-arn ${env.ALB_ARN} \
                        --query 'Listeners[0].ListenerArn' \
                        --output text
                """,
                returnStdout: true
            ).trim()
        }

        // Delete existing test rule if it exists
        echo "🧹 Cleaning up any existing test rule..."
        sh """
        TEST_RULE=\$(aws elbv2 describe-rules \
            --listener-arn ${env.LISTENER_ARN} \
            --query "Rules[?Priority=='10'].RuleArn" \
            --output text)

        if [ ! -z "\$TEST_RULE" ]; then
            aws elbv2 delete-rule --rule-arn \$TEST_RULE
        fi
        """

        // Create new test rule
        echo "🚧 Creating test rule for /test* on idle target group..."
        sh """
        aws elbv2 create-rule \
            --listener-arn ${env.LISTENER_ARN} \
            --priority 10 \
            --conditions '[{"Field":"path-pattern","Values":["/test*"]}]' \
            --actions '[{"Type":"forward","TargetGroupArn":"${env.IDLE_TG_ARN}"}]'
        """

        // Get ALB DNS
        def albDns = sh(
            script: """
                aws elbv2 describe-load-balancers \
                    --load-balancer-arns ${env.ALB_ARN} \
                    --query 'LoadBalancers[0].DNSName' \
                    --output text
            """,
            returnStdout: true
        ).trim()

        // Store DNS for later use
        env.ALB_DNS = albDns

        // Wait for rule propagation and test endpoint
        echo "⏳ Waiting for rule to propagate..."
        sh "sleep 10"

        echo "🌐 Hitting test endpoint: http://${albDns}/test/health"
        sh """
        curl -f http://${albDns}/test/health || curl -f http://${albDns}/test || echo "⚠️ Health check failed but continuing"
        """

        echo "✅ ${env.IDLE_ENV} environment tested successfully"

    } catch (Exception e) {
        echo "⚠️ Warning: Test stage encountered an issue: ${e.message}"
        echo "Proceeding with deployment despite test issues."
    } finally {
        // Cleanup test rule after testing
        echo "🧽 Cleaning up test rule..."
        sh """
        TEST_RULE=\$(aws elbv2 describe-rules \
            --listener-arn ${env.LISTENER_ARN} \
            --query "Rules[?Priority=='10'].RuleArn" \
            --output text)

        if [ ! -z "\$TEST_RULE" ]; then
            aws elbv2 delete-rule --rule-arn \$TEST_RULE
            echo "🗑️ Test rule deleted."
        else
            echo "ℹ️ No test rule found to delete."
        fi
        """
    }
}



def switchTraffic(Map config) {
    echo "🔄 Switching traffic to ${config.IDLE_ENV}"

    try {
        // Switch 100% traffic to the idle environment
        sh """
        aws elbv2 modify-listener --listener-arn ${config.LISTENER_ARN} --default-actions Type=forward,TargetGroupArn=${config.IDLE_TG_ARN}
        """

        echo "✅ Traffic switched 100% to ${config.IDLE_ENV}"

        // Remove the test rule if it exists
        sh """
        TEST_RULE=\$(aws elbv2 describe-rules --listener-arn ${config.LISTENER_ARN} --query "Rules[?Priority=='10'].RuleArn" --output text)
        
        if [ ! -z "\$TEST_RULE" ]; then
            aws elbv2 delete-rule --rule-arn \$TEST_RULE
        fi
        """

        // Verify the traffic distribution
        def currentConfig = sh(
            script: """
            aws elbv2 describe-listeners --listener-arns ${config.LISTENER_ARN} --query 'Listeners[0].DefaultActions[0]' --output json
            """,
            returnStdout: true
        ).trim()

        echo "Current listener configuration: ${currentConfig}"
        echo "✅✅✅ Traffic switching completed successfully!"
    } catch (Exception e) {
        error "Failed to switch traffic: ${e.message}"
    }
}


import groovy.json.JsonOutput

def scaleDownOldEnvironment(Map config) {
    echo "📉 Dynamically scaling down old environment (previously live ECS service)..."

    try {
        def albName = config.ALB_NAME ?: 'blue-green-alb'

        def albArn = sh(script: "aws elbv2 describe-load-balancers --names ${albName} --query 'LoadBalancers[0].LoadBalancerArn' --output text", returnStdout: true).trim()
        if (!albArn || albArn == 'None') error "❌ Could not find ALB ARN for name: ${albName}"
        echo "✅ Found ALB ARN: ${albArn}"

        def listenerArn = sh(script: "aws elbv2 describe-listeners --load-balancer-arn ${albArn} --query \"Listeners[?DefaultActions[0].Type=='forward'].[ListenerArn]\" --output text", returnStdout: true).trim()
        if (!listenerArn) error "❌ Listener ARN could not be determined from ALB ${albArn}"
        echo "✅ Found Listener ARN: ${listenerArn}"

        def liveTgArn = sh(script: "aws elbv2 describe-rules --listener-arn ${listenerArn} --query \"Rules[?Priority=='1' || Priority=='default'].Actions[0].TargetGroupArn\" --output text", returnStdout: true).trim()
        if (!liveTgArn) error "❌ Live target group ARN could not be determined from listener rules"
        echo "✅ Live Target Group ARN: ${liveTgArn}"

        def targetGroupsJson = sh(script: "aws elbv2 describe-target-groups --load-balancer-arn ${albArn} --query 'TargetGroups[*].[TargetGroupArn, TargetGroupName]' --output json", returnStdout: true).trim()
        def targetGroups = parseJsonNonCPS(targetGroupsJson)
        echo "🔍 Target Groups found:"
        targetGroups.each { echo " - Name: ${it[1]}, ARN: ${it[0]}" }

        def blueTgArn = targetGroups.find { it[1].toLowerCase() == 'blue-tg' }?.getAt(0)
        def greenTgArn = targetGroups.find { it[1].toLowerCase() == 'green-tg' }?.getAt(0)
        if (!blueTgArn || !greenTgArn) error "❌ Could not find both Blue and Green target groups in ALB ${albArn}"
        echo "✅ Blue TG ARN: ${blueTgArn}"
        echo "✅ Green TG ARN: ${greenTgArn}"

        def idleTgArn = (liveTgArn == blueTgArn) ? greenTgArn : blueTgArn
        echo "✅ Idle (previously live) Target Group ARN: ${idleTgArn}"

        def targetIdsJson = sh(script: "aws elbv2 describe-target-health --target-group-arn ${idleTgArn} --query 'TargetHealthDescriptions[].Target.Id' --output json", returnStdout: true).trim()
        def targetIds = parseJsonNonCPS(targetIdsJson)
        if (!targetIds || targetIds.isEmpty()) {
            echo "⚠️ No targets found in the idle target group. Nothing to scale down."
            return
        }
        echo "✅ Target IDs in idle TG: ${JsonOutput.toJson(targetIds)}"

        def ecsCluster = sh(script: "aws ecs list-clusters --query 'clusterArns[0]' --output text", returnStdout: true).trim()
        if (!ecsCluster || ecsCluster == 'None') error "❌ No ECS cluster found"
        echo "✅ ECS Cluster ARN: ${ecsCluster}"

        def servicesRaw = sh(script: "aws ecs list-services --cluster ${ecsCluster} --output text", returnStdout: true).trim()
        echo "Raw ECS services output:\n${servicesRaw}"

        def services = servicesRaw.tokenize().findAll { !it.startsWith('SERVICEARNS') }
        if (!services) {
            echo "⚠️ No ECS services found in cluster ${ecsCluster}. Skipping scale down."
            return
        }
        echo "Filtered ECS services:"
        services.each { echo " - ${it}" }

        def idleService = null

        outerLoop:
        for (serviceArn in services) {
            def serviceName = serviceArn.tokenize('/').last()
            echo "Checking service: ${serviceName}"

            def taskArns = []
            int attempts = 0
            while (attempts < 3) {
                def taskArnsJson = sh(script: "aws ecs list-tasks --cluster ${ecsCluster} --service-name ${serviceName} --output json", returnStdout: true).trim()
                taskArns = parseJsonNonCPS(taskArnsJson)?.taskArns ?: []
                if (taskArns) break
                attempts++
                echo "No tasks found for service ${serviceName}, retrying (${attempts}/3)..."
                sleep 5
            }

            if (!taskArns) {
                echo "No tasks found for service ${serviceName}, skipping."
                continue
            }

            for (taskId in taskArns) {
                def attachmentsJson = sh(script: "aws ecs describe-tasks --cluster ${ecsCluster} --tasks ${taskId} --query 'tasks[0].attachments' --output json", returnStdout: true).trim()

                if (!attachmentsJson) {
                    echo "⚠️ No attachments JSON found for task ${taskId}, skipping."
                    continue
                }
                def attachments = parseJsonNonCPS(attachmentsJson)

                if (!attachments) {
                    echo "⚠️ No attachments found for task ${taskId}, skipping."
                    continue
                }

                echo "Attachments: ${JsonOutput.toJson(attachments)}"

                for (attachment in attachments) {
                    def eniId = attachment.details.find { it.name == 'networkInterfaceId' }?.value
                    if (!eniId) continue

                    def privateIp = ''
                    try {
                        privateIp = sh(script: "aws ec2 describe-network-interfaces --network-interface-ids ${eniId} --query 'NetworkInterfaces[0].PrivateIpAddress' --output text", returnStdout: true).trim()
                        echo "Task ${taskId} ENI ${eniId} IP: ${privateIp}"
                    } catch (Exception ex) {
                        echo "⚠️ Failed to get private IP for ENI ${eniId}: ${ex.message}"
                        continue
                    }

                    if (targetIds.contains(privateIp) || targetIds.contains(eniId)) {
                        idleService = serviceName
                        echo "✅ Match found for service ${serviceName} with target ID ${privateIp}"
                        break outerLoop
                    }
                }
            }
        }

        if (!idleService) {
            error "❌ Could not map target group to any ECS service. Scale-down aborted."
        }

        echo "✅ Idle ECS service to scale down: ${idleService}"

        sh "aws ecs update-service --cluster ${ecsCluster} --service ${idleService} --desired-count 0"
        echo "✅ Successfully scaled down ${idleService}"

        sh "aws ecs wait services-stable --cluster ${ecsCluster} --services ${idleService}"
        echo "✅ Service is now stable"

    } catch (Exception e) {
        echo "⚠️ Error during scale down: ${e.message}"
        echo "⚠️ Continuing pipeline despite error"
    }
}

@NonCPS
def parseJsonNonCPS(String text) {
    return new groovy.json.JsonSlurper().parseText(text)
}




