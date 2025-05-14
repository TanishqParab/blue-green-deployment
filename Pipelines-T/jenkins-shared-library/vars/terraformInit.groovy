// vars/terraformInit.groovy

def call(String workingDir) {
    echo "Initializing Terraform..."
    dir(workingDir) {
        sh "terraform init"
    }
}
