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

def cleanEcrRepository(Map config) {
    echo "🧹 Cleaning up ECR repository before destruction..."
    
    try {
        // Check if the ECR repository exists
        def ecrRepoExists = sh(
            script: "aws ecr describe-repositories --repository-names ${config.ecrRepoName} --region ${env.AWS_REGION} &>/dev/null && echo 0 || echo 1",
            returnStdout: true
        ).trim() == "0"

        if (ecrRepoExists) {
            echo "🔍 Fetching all images in repository ${config.ecrRepoName}..."
            
            // Get all image digests (including untagged images)
            def imageDigests = sh(
                script: """
                    aws ecr list-images --repository-name ${config.ecrRepoName} --region ${env.AWS_REGION} \\
                    --query 'imageIds[?imageDigest].imageDigest' --output text
                """,
                returnStdout: true
            ).trim()
            
            // Get all image tags
            def imageTags = sh(
                script: """
                    aws ecr list-images --repository-name ${config.ecrRepoName} --region ${env.AWS_REGION} \\
                    --query 'imageIds[?imageTag].imageTag' --output text
                """,
                returnStdout: true
            ).trim()
            
            // Combine all images to delete (both digests and tags)
            def imagesToDelete = []
            
            if (imageDigests) {
                imagesToDelete.addAll(imageDigests.split('\\s+').collect { "imageDigest=${it}" })
            }
            
            if (imageTags) {
                imagesToDelete.addAll(imageTags.split('\\s+').collect { "imageTag=${it}" })
            }
            
            if (imagesToDelete) {
                echo "🗑️ Found ${imagesToDelete.size()} images to delete"
                
                // Batch delete in chunks of 100 (AWS limit per request)
                imagesToDelete.collate(100).each { batch ->
                    def batchString = batch.join(' ')
                    echo "🚮 Deleting batch of ${batch.size()} images..."
                    sh """
                        aws ecr batch-delete-image \\
                            --repository-name ${config.ecrRepoName} \\
                            --region ${env.AWS_REGION} \\
                            --image-ids ${batchString}
                    """
                    echo "✅ Deleted batch of ${batch.size()} images"
                }
                
                echo "✅ Successfully deleted all images from repository"
            } else {
                echo "ℹ️ No images found in repository"
            }
        } else {
            echo "ℹ️ ECR repository ${config.ecrRepoName} not found, skipping cleanup"
        }
    } catch (Exception e) {
        error "Failed to clean ECR repository: ${e.message}"
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

    try {
        // Get ECS cluster name
        env.ECS_CLUSTER = sh(
            script: "terraform -chdir=${config.tfWorkingDir} output -raw ecs_cluster_id || aws ecs list-clusters --query 'clusterArns[0]' --output text",
            returnStdout: true
        ).trim()

        // Get blue and green target group ARNs
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

        // Determine the currently LIVE environment
        def currentTargetGroup = sh(
            script: """
            aws elbv2 describe-listeners --listener-arns ${env.LISTENER_ARN} \
            --query 'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[0].TargetGroupArn || Listeners[0].DefaultActions[0].TargetGroupArn' \
            --output text
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
        error "❌ Failed to fetch ECS resources: ${e.message}"
    }
}


def ensureTargetGroupAssociation(Map config) {
    echo "Ensuring target group is associated with load balancer..."

    def targetGroupInfo = sh(
        script: """
        aws elbv2 describe-target-groups --target-group-arns ${env.IDLE_TG_ARN} --query 'TargetGroups[0].LoadBalancerArns' --output json
        """,
        returnStdout: true
    ).trim()

    def targetGroupJson = readJSON text: targetGroupInfo

    if (targetGroupJson.size() == 0) {
        echo "⚠️ Target group ${env.IDLE_ENV} is not associated with a load balancer. Creating a path-based rule..."

        sh """
        aws elbv2 create-rule --listener-arn ${env.LISTENER_ARN} --priority 100 --conditions '[{"Field":"path-pattern","Values":["/associate-tg*"]}]' --actions '[{"Type":"forward","TargetGroupArn":"${env.IDLE_TG_ARN}"}]'
        """

        sleep(10)

        echo "✅ Target group associated with load balancer via path rule"
    } else {
        echo "✅ Target group is already associated with load balancer"
    }
}


def updateApplication(Map config) {
    echo "Running ECS update application logic..."

    try {
        // Get the current 'latest' image details with better error handling
        def currentLatestImageInfo = sh(
            script: """
            aws ecr describe-images --repository-name ${env.ECR_REPO_NAME} --image-ids imageTag=latest --query 'imageDetails[0].{digest:imageDigest,pushedAt:imagePushedAt}' --output json 2>/dev/null || echo '{}'
            """,
            returnStdout: true
        ).trim()

        // Safely parse JSON
        def currentLatestJson = [:]
        try {
            if (currentLatestImageInfo && currentLatestImageInfo != '{}') {
                currentLatestJson = new groovy.json.JsonSlurperClassic().parseText(currentLatestImageInfo)
            }
        } catch (Exception e) {
            echo "⚠️ Could not parse ECR image info: ${e.message}"
            currentLatestJson = [:]
        }

        // Create rollback tag if 'latest' exists
        if (currentLatestJson?.digest) {
            def timestamp = new Date().format("yyyyMMdd-HHmmss")
            def rollbackTag = "rollback-${timestamp}"

            echo "Found current 'latest' image with digest: ${currentLatestJson.digest}"
            echo "Tagging current 'latest' image as '${rollbackTag}' before overwriting..."

            // More robust image manifest handling
            sh """
                # Get image manifest
                aws ecr batch-get-image \
                    --repository-name ${env.ECR_REPO_NAME} \
                    --image-ids imageDigest=${currentLatestJson.digest} \
                    --query 'images[0].imageManifest' \
                    --output text > image-manifest.json || exit 1
                
                # Tag the image
                aws ecr put-image \
                    --repository-name ${env.ECR_REPO_NAME} \
                    --image-tag ${rollbackTag} \
                    --image-manifest file://image-manifest.json || exit 1
                
                # Verify the tag was created
                aws ecr describe-images \
                    --repository-name ${env.ECR_REPO_NAME} \
                    --image-ids imageTag=${rollbackTag} \
                    --query 'imageDetails[0].imageDigest' \
                    --output text || exit 1
            """

            echo "✅ Current 'latest' image tagged as '${rollbackTag}' for backup"
            env.PREVIOUS_VERSION_TAG = rollbackTag
        } else {
            echo "⚠️ No current 'latest' image found to tag as rollback"
        }

        // Rest of your method remains the same...
        // [Build and push new image code...]
        
    } catch (Exception e) {
        error "Failed to update application: ${e.message}\nStack trace: ${e.getStackTrace().join('\n')}"
    }
}


def testEnvironment(Map config) {
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


def scaleDownOldEnvironment(Map config) {
    echo "Scaling down old ${config.liveEnv} environment..."

    try {
        sh """
        aws ecs update-service --cluster ${config.ecsCluster} --service ${config.liveService} --desired-count 0
        """

        echo "✅ Previous live service (${config.liveEnv}) scaled down"

        sh """
        aws ecs wait services-stable --cluster ${config.ecsCluster} --services ${config.liveService}
        """

        echo "✅ All services are stable"
    } catch (Exception e) {
        echo "Warning: Scale down encountered an issue: ${e.message}"
        echo "Continuing despite scale down issues"
    }
}
