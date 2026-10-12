import PipelineOrchestrator

def call() {
    // 1. Read the developer's YAML file checked out from GitHub
    def yamlConfig = readYaml file: 'pipeline.yaml'
    
    // 2. Instantiate the orchestrator, passing 'this' (the Jenkins context)
    def orchestrator = new PipelineOrchestrator(this)
    
    // 3. Kick off execution
    orchestrator.execute(yamlConfig)
}
