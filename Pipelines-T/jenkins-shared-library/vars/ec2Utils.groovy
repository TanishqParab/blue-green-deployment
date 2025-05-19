// vars/ec2Utils.groovy


def registerInstancesToTargetGroups(Map config) {
    echo "📡 Registering EC2 instances to target groups..."

    // Fetch Target Group ARNs
    def blueTgArn = sh(
        script: """
        aws elbv2 describe-target-groups --names "blue-tg" --query 'TargetGroups[0].TargetGroupArn' --output text
        """,
        returnStdout: true
    ).trim()

    def greenTgArn = sh(
        script: """
        aws elbv2 describe-target-groups --names "green-tg" --query 'TargetGroups[0].TargetGroupArn' --output text
        """,
        returnStdout: true
    ).trim()

    if (!blueTgArn || !greenTgArn) {
        error "❌ Failed to fetch Target Group ARNs! Check if they exist in AWS."
    }

    echo "✅ Blue Target Group ARN: ${blueTgArn}"
    echo "✅ Green Target Group ARN: ${greenTgArn}"

    // Fetch EC2 instance IDs
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
        aws elbv2 deregister-targets --target-group-arn ${blueTgArn} --targets Id=${greenInstanceId}
        aws elbv2 deregister-targets --target-group-arn ${greenTgArn} --targets Id=${blueInstanceId}
    """
    sleep(10)

    echo "✅ Registering instances to the correct target groups..."
    sh """
        aws elbv2 register-targets --target-group-arn ${blueTgArn} --targets Id=${blueInstanceId}
        aws elbv2 register-targets --target-group-arn ${greenTgArn} --targets Id=${greenInstanceId}
    """

    echo "🎯 EC2 instances successfully registered to correct target groups!"
}


def detectChanges(Map config) {
    echo "🔍 Detecting changes for EC2 implementation..."

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
}


def fetchResources(Map config) {
    echo "🔍 Fetching Target Group ARNs..."

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
}




def updateApplication(Map config) {
    echo "Running EC2 update application logic..."

    // Register Instances to Target Groups
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
    sleep(10) // Allow time for deregistration

    echo "✅ Registering instances to the correct target groups..."
    sh """
        aws elbv2 register-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${blueInstanceId}
        aws elbv2 register-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${greenInstanceId}
    """

    echo "✅ Instances successfully registered to correct target groups!"
}

def deployToBlueInstance(Map config) {
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


def switchTraffic(Map config) {
    echo "🔄 Fetching ALB listener ARN..."

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
        --default-actions Type=forward,TargetGroupArn=${config.BLUE_TG_ARN}
    """

    // Verification
    def currentDefaultAction = sh(script: """
        aws elbv2 describe-listeners --listener-arns ${listenerArn} \
        --query 'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[0].TargetGroupArn' \
        --output text
    """, returnStdout: true).trim()

    if (currentDefaultAction != config.BLUE_TG_ARN) {
        error "❌ Verification failed! Default action not pointing to BLUE target group"
    }

    echo "✅✅✅ Traffic switching completed successfully!"
    echo "============================================="
    echo "CURRENT ROUTING:"
    echo "- Default route: 100% to BLUE (${config.BLUE_TG_ARN})"
    echo "- No path-based or weighted rules active"
}


def tagSwapInstances(Map config) {
    echo "🌐 Discovering AWS resources..."

    def instances = sh(script: """
        aws ec2 describe-instances \\
            --filters "Name=tag:Name,Values=${config.blueTag},${config.greenTag}" \\
                     "Name=instance-state-name,Values=running" \\
            --query 'Reservations[].Instances[].[InstanceId,Tags[?Key==\\\`Name\\\`].Value | [0]]' \\
            --output json
    """, returnStdout: true).trim()

    def instancesJson = readJSON text: instances
    def blueInstance = null
    def greenInstance = null

    for (instance in instancesJson) {
        if (instance[1] == config.blueTag) {
            blueInstance = instance[0]
        } else if (instance[1] == config.greenTag) {
            greenInstance = instance[0]
        }
    }

    if (!blueInstance || !greenInstance) {
        error "❌ Could not find both Blue and Green running instances. Found:\n${instancesJson}"
    }
    echo "✔️ Found instances - Blue: ${blueInstance}, Green: ${greenInstance}"

    echo "🔄 Performing atomic tag swap..."

    sh """
        #!/bin/bash
        set -euo pipefail

        if [ -z "${blueInstance}" ] || [ -z "${greenInstance}" ]; then
            echo "❌ Missing instance IDs"
            exit 1
        fi

        aws ec2 create-tags --resources ${blueInstance} --tags Key=Name,Value=${config.greenTag}
        aws ec2 create-tags --resources ${greenInstance} --tags Key=Name,Value=${config.blueTag}

        blue_tag=\$(aws ec2 describe-tags --filters "Name=resource-id,Values=${blueInstance}" "Name=key,Values=Name" --query "Tags[0].Value" --output text)
        green_tag=\$(aws ec2 describe-tags --filters "Name=resource-id,Values=${greenInstance}" "Name=key,Values=Name" --query "Tags[0].Value" --output text)

        if [ "\$blue_tag" != "${config.greenTag}" ] || [ "\$green_tag" != "${config.blueTag}" ]; then
            echo "❌ Tag verification failed!"
            exit 1
        fi
    """

    echo "✅ Deployment Complete!"
    echo "====================="
    echo "Instance Tags:"
    echo "- ${blueInstance} (now ${config.greenTag})"
    echo "- ${greenInstance} (now ${config.blueTag})"
}
