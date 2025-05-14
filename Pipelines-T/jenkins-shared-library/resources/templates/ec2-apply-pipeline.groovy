// resources/templates/ec2-apply-pipeline.groovy

def call(Map config = [:]) {
    // Default EC2-specific configurations
    def ec2Config = [
        implementation: 'ec2',
        sshKeyId: 'blue-green-key',
        repoUrl: 'https://github.com/TanishqParab/blue-green-deployment',
        appFile: 'app.py'
    ]
    
    // Merge with user-provided config
    config = ec2Config + config
    
    // Call the base pipeline with EC2 config
    return baseTemplate(config)
}
