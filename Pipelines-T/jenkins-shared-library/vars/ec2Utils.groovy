// vars/ec2Utils.groovy

def deployApplication(Map config) {
    echo "Waiting for instances to start..."
    sleep(60)  // Give time for instances to fully boot

    echo "Checking instance states..."
    sh """
    aws ec2 describe-instances \
    --filters "Name=tag:Environment,Values=Blue-Green" \
    --query 'Reservations[*].Instances[*].[InstanceId, State.Name]' \
    --output table
    """

    echo "Retrieving instance IPs..."
    def instances = sh(
        script: """
        aws ec2 describe-instances \
        --filters "Name=tag:Environment,Values=Blue-Green" "Name=instance-state-name,Values=running" \
        --query 'Reservations[*].Instances[*].PublicIpAddress' \
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
}
