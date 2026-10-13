import builders.JavaBuilder
import builders.GoBuilder
import deployers.K8sDeployer
import deployers.VmDeployer

class PipelineOrchestrator implements Serializable {
    def steps
    
    PipelineOrchestrator(steps) {
        this.steps = steps
    }
    
    // Split into clear, executable steps called by the wrapper pipeline
    def executeBuild() {
        def yamlConfig = steps.readYaml file: 'pipeline.yaml'
        steps.echo "Compiling code for stack: ${yamlConfig.stack} (Version: ${yamlConfig.version})"
        
        if (yamlConfig.stack == 'java') {
            new JavaBuilder(steps).build(yamlConfig)
        } else if (yamlConfig.stack == 'golang') {
            new GoBuilder(steps).build(yamlConfig)
        } else {
            steps.error "Unsupported programming stack: ${yamlConfig.stack}"
        }
    }
    
    def executeDeploy() {
        def yamlConfig = steps.readYaml file: 'pipeline.yaml'
        steps.echo "Routing deployment to target: ${yamlConfig.deployment.type}"
        
        if (yamlConfig.deployment.type == 'kubernetes') {
            new K8sDeployer(steps).deploy(yamlConfig.deployment)
        } else if (yamlConfig.deployment.type == 'vm') {
            new VmDeployer(steps).deploy(yamlConfig.deployment)
        } else {
            steps.error "Unsupported deployment target: ${yamlConfig.deployment.type}"
        }
    }
}
