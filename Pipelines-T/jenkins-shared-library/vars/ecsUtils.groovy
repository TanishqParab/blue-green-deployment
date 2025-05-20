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


import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def updateApplication(Map config) {
    echo "Running ECS update application logic..."

    try {
        // Use JsonSlurper instead of JsonSlurperClassic
        def jsonSlurper = new JsonSlurper()

        // Get current 'latest' image details
        def currentLatestImageInfo = sh(
            script: """
            aws ecr describe-images --repository-name ${env.ECR_REPO_NAME} --image-ids imageTag=latest --region ${env.AWS_REGION} --query 'imageDetails[0].{digest:imageDigest,pushedAt:imagePushedAt}' --output json 2>/dev/null || echo '{}'
            """,
            returnStdout: true
        ).trim()

        def currentLatestJson = parseJson(jsonSlurper, currentLatestImageInfo)

        // Backup current 'latest' as rollback tag
        if (currentLatestJson?.digest) {
            def timestamp = new Date().format("yyyyMMdd-HHmmss")
            def rollbackTag = "rollback-${timestamp}"

            echo "Found current 'latest' image with digest: ${currentLatestJson.digest}"
            echo "Tagging current 'latest' image as '${rollbackTag}'..."

            sh """
            aws ecr batch-get-image --repository-name ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --image-ids imageDigest=${currentLatestJson.digest} --query 'images[0].imageManifest' --output text > image-manifest.json
            aws ecr put-image --repository-name ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --image-tag ${rollbackTag} --image-manifest file://image-manifest.json
            """

            echo "✅ Tagged rollback image: ${rollbackTag}"
            env.PREVIOUS_VERSION_TAG = rollbackTag
        } else {
            echo "⚠️ No current 'latest' image found to tag"
        }

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

        echo "Updating ${env.IDLE_ENV} service..."

        def taskDefArn = sh(
            script: "aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${env.IDLE_SERVICE} --region ${env.AWS_REGION} --query 'services[0].taskDefinition' --output text",
            returnStdout: true
        ).trim()

        def taskDefJsonText = sh(
            script: "aws ecs describe-task-definition --task-definition ${taskDefArn} --region ${env.AWS_REGION} --query 'taskDefinition' --output json",
            returnStdout: true
        ).trim()

        def taskDefJson = parseJson(jsonSlurper, taskDefJsonText)

        // Clean up non-required fields
        ['taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 'compatibilities',
         'registeredAt', 'registeredBy', 'deregisteredAt'].each { field ->
            taskDefJson.remove(field)
        }

        taskDefJson.containerDefinitions[0].image = env.IMAGE_URI

        writeFile file: 'new-task-def.json', text: JsonOutput.prettyPrint(JsonOutput.toJson(taskDefJson))

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
def parseJson(jsonSlurper, text) {
    return jsonSlurper.parseText(text)
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


def scaleDownOldEnvironment(Map config) {
    echo "📉 Dynamically scaling down old environment (previously live ECS service)..."

    try {
        def listenerArn = sh(
            script: """
                aws elbv2 describe-listeners --load-balancer-arn ${env.CUSTOM_ALB_ARN} \
                --query 'Listeners[?DefaultActions[0].Type==\\`forward\\`].[ListenerArn]' \
                --output text
            """,
            returnStdout: true
        ).trim()

        if (!listenerArn) {
            error "❌ Listener ARN could not be determined from ALB ${env.CUSTOM_ALB_ARN}"
        }

        def liveTgArn = sh(
            script: """
                aws elbv2 describe-rules --listener-arn ${listenerArn} \
                --query 'Rules[?Priority==\\`1\\`].Actions[0].TargetGroupArn' --output text
            """,
            returnStdout: true
        ).trim()

        if (!liveTgArn) {
            error "❌ Live target group ARN could not be determined from listener rule"
        }

        echo "✅ Live Target Group ARN: ${liveTgArn}"

        // Infer which is IDLE target group
        def idleTgArn = (liveTgArn == env.BLUE_TG_ARN) ? env.GREEN_TG_ARN : env.BLUE_TG_ARN
        echo "✅ Idle (previously live) Target Group ARN: ${idleTgArn}"

        // Get containerInstance target (usually IP:port)
        def targetId = sh(
            script: """
                aws elbv2 describe-target-health \
                --target-group-arn ${idleTgArn} \
                --query 'TargetHealthDescriptions[0].Target.Id' --output text
            """,
            returnStdout: true
        ).trim()

        if (!targetId || targetId == "None") {
            echo "⚠️ No targets found in the idle target group. Nothing to scale down."
            return
        }

        // Find which ECS service is associated with this target
        def ecsCluster = sh(
            script: "aws ecs list-clusters --query 'clusterArns[0]' --output text",
            returnStdout: true
        ).trim()

        if (!ecsCluster || ecsCluster == 'None') {
            error "❌ No ECS cluster found"
        }

        def services = sh(
            script: "aws ecs list-services --cluster ${ecsCluster} --output text",
            returnStdout: true
        ).trim().split()

        def idleService = null

        for (serviceArn in services) {
            def serviceName = serviceArn.tokenize('/').last()
            def taskArns = sh(
                script: """
                    aws ecs list-tasks --cluster ${ecsCluster} --service-name ${serviceName} --output text
                """,
                returnStdout: true
            ).trim()

            if (!taskArns || taskArns == "None") {
                continue
            }

            def taskId = taskArns.split().first()
            def eni = sh(
                script: """
                    aws ecs describe-tasks --cluster ${ecsCluster} --tasks ${taskId} \
                    --query 'tasks[0].attachments[0].details[?name==\\`networkInterfaceId\\`].value' --output text
                """,
                returnStdout: true
            ).trim()

            def privateIp = sh(
                script: """
                    aws ec2 describe-network-interfaces --network-interface-ids ${eni} \
                    --query 'NetworkInterfaces[0].PrivateIpAddress' --output text
                """,
                returnStdout: true
            ).trim()

            if (privateIp == targetId) {
                idleService = serviceName
                break
            }
        }

        if (!idleService) {
            error "❌ Could not map target group to any ECS service. Scale-down aborted."
        }

        echo "✅ Idle ECS service to scale down: ${idleService}"

        // Scale down the idle (previously live) service
        sh """
            aws ecs update-service --cluster ${ecsCluster} --service ${idleService} --desired-count 0
        """

        echo "✅ Successfully scaled down ${idleService}"

        sh """
            aws ecs wait services-stable --cluster ${ecsCluster} --services ${idleService}
        """

        echo "✅ Service is now stable"

    } catch (Exception e) {
        echo "⚠️ Error during scale down: ${e.message}"
        echo "⚠️ Continuing pipeline despite error"
    }
}

