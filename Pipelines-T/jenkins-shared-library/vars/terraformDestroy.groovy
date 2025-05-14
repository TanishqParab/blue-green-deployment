// vars/terraformDestroy.groovy

def call(Map config) {
    def varFileOption = config.varFile ? "-var-file=${config.varFile}" : ""
    
    // Get the build number containing the state
    def buildNumber = input(
        message: "Enter the build number that created the infrastructure (e.g., 42)",
        parameters: [string(name: 'BUILD_NUMBER')]
    )

    // Fetch the archived state file
    dir(config.tfWorkingDir) {
        copyArtifacts(
            projectName: env.JOB_NAME,
            selector: specific("${buildNumber}"),
            filter: "terraform.tfstate",
            target: "."
        )
    }

    // Initialize and destroy
    dir(config.tfWorkingDir) {
        sh "terraform init"
        sh "terraform destroy ${varFileOption} -auto-approve"
    }
}
