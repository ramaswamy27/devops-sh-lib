import builders.JavaBuilder
import builders.GoBuilder
import deployers.K8sDeployer
import deployers.VmDeployer

class PipelineOrchestrator implements Serializable {
    def steps
    
    PipelineOrchestrator(steps) {
        this.steps = steps
    }
    
    def execute(Map yamlConfig) {
        // Enforce the standard Declarative Pipeline structure programmatically
        steps.pipeline {
            steps.agent any
            
            steps.stages {
                steps.stage('Build & Test Artifact') {
                    steps.steps {
                        steps.script {
                            steps.echo "Compiling code for stack: ${yamlConfig.stack} (Version: ${yamlConfig.version})"
                            
                            if (yamlConfig.stack == 'java') {
                                new JavaBuilder(steps).build(yamlConfig)
                            } else if (yamlConfig.stack == 'golang') {
                                new GoBuilder(steps).build(yamlConfig)
                            } else {
                                steps.error "Unsupported programming stack: ${yamlConfig.stack}"
                            }
                        }
                    }
                }
                
                steps.stage('Secure Infrastructure Deployment') {
                    steps.steps {
                        steps.script {
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
                }
            }
        }
    }
}
