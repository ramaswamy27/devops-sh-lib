import PipelineOrchestrator

def call() {
    // 1. Instantiate the orchestrator, passing 'this' (the Jenkins context)
    def orchestrator = new PipelineOrchestrator(this)
    
    // 2. Kick off execution
    orchestrator.execute(yamlConfig)
}
