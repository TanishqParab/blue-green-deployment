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
