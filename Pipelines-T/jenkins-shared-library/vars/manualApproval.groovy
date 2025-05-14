// vars/manualApproval.groovy

def call(Map config) {
    def varFileOption = config.varFile ? "-var-file=${config.varFile}" : ""
    
    dir(config.tfWorkingDir) {
        // Generate and save the full Terraform plan to a file
        sh "terraform plan ${varFileOption} -no-color > tfplan.txt"

        // Read the full plan for logging purposes
        def tfPlan = readFile('tfplan.txt')

        // Archive the plan as an artifact for download
        archiveArtifacts artifacts: 'tfplan.txt', fingerprint: true

        // Log plan to console for visibility
        echo "========== Terraform Plan Start =========="
        echo tfPlan
        echo "========== Terraform Plan End ============"

        // Construct artifact download link
        def planDownloadLink = "${env.BUILD_URL}artifact/tfplan.txt"

        // Email for approval with download link
        emailext (
            to: config.emailRecipient,
            subject: "Approval required for Terraform apply - Build ${currentBuild.number}",
            body: """
                Hi,

                A Terraform apply requires your approval.

                👉 Review the Terraform plan here (download full plan):
                ${planDownloadLink}

                Once reviewed, please approve or abort the deployment at:
                ${env.BUILD_URL}input

                Regards,  
                Jenkins Automation
            """,
            replyTo: config.emailRecipient
        )

        // Input prompt for manual approval
        timeout(time: 1, unit: 'HOURS') {
            input(
                id: 'ApplyApproval',
                message: "Terraform Apply Approval Required",
                ok: "Apply",
                parameters: [],
                description: """⚠️ Full plan is too long for this screen.

✅ Check the full plan in:
- [tfplan.txt Artifact](${planDownloadLink})
- Console Output (above this stage)"""
            )
        }
    }
}
