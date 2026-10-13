import PipelineOrchestrator

def call() {
    // 1. Establish the declarative framework natively at the script level
    pipeline {
        agent any
        
        stages {
            stage('Build & Test Artifact') {
                steps {
                    script {
                        // 2. Instantiate and run the orchestrator step
                        def orchestrator = new PipelineOrchestrator(this)
                        orchestrator.executeBuild()
                    }
                }
            }
            stage('Secure Infrastructure Deployment') {
                steps {
                    script {
                        // 3. Execute the deployment step
                        def orchestrator = new PipelineOrchestrator(this)
                        orchestrator.executeDeploy()
                    }
                }
            }
        }
    }
}
