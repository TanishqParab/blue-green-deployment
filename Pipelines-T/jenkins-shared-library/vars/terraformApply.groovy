// vars/terraformApply.groovy

def call(Map config) {
    echo "Running Terraform apply"
    dir(config.tfWorkingDir) {
        sh "terraform apply -auto-approve tfplan"
        
        // Save the state file
        archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true 
    }
}
