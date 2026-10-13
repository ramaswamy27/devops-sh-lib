import PipelineOrchestrator

def call() {
    pipeline {
        agent none // Dynamic builder classes allocate their own isolated environments
        
        stages {
            stage('Dynamic Orchestrated Build') {
                // Allocate a temporary workspace manager just to parse the pipeline configurations
                agent any 
                steps {
                    script {
                        def orchestrator = new PipelineOrchestrator(this)
                        orchestrator.executeBuild()
                    }
                }
            }
            stage('Dynamic Orchestrated Deployment') {
                agent any
                steps {
                    script {
                        def orchestrator = new PipelineOrchestrator(this)
                        orchestrator.executeDeploy()
                    }
                }
            }
        }
    }
}
