// vars/terraformInit.groovy

def call(config) {
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
}
