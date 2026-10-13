import builders.JavaBuilder
import builders.GoBuilder
import deployers.K8sDeployer
import deployers.VmDeployer

class PipelineOrchestrator implements Serializable {
    def steps
    
    PipelineOrchestrator(steps) {
        this.steps = steps
    }
    
    def execute() {
        steps.pipeline {
            steps.agent any // <-- This allocates the required hudson.FilePath context variable!
            
            steps.stages {
                steps.stage('Parse & Build Artifact') {
                    steps.steps {
                        steps.script {
                            // 1. Safe to read file now that a workspace context is present
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
                    }
                }
                
                steps.stage('Secure Infrastructure Deployment') {
                    steps.steps {
                        steps.script {
                            // 2. Re-read or pass the object to your deployment block
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
                }
            }
        }
    }
}
