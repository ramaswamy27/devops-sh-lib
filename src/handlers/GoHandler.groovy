package handlers

class GoHandler implements Serializable {
    private def steps
    
    GoHandler(steps) {
        this.steps = steps
    }

    def preBuild(String containerName, def config) {
        steps.echo "Verifying Go workspace modules configuration..."
    }

    def build(String containerName, def config) {
        if (!config.buildCmd) { steps.error "Go Build Command missing." }
        steps.sh "docker exec ${containerName} ${config.buildCmd}"
    }

    def test(String containerName, def config) {
        if (!config.testCmd) { steps.error "Go Test Command missing." }
        steps.sh "docker exec ${containerName} ${config.testCmd}"
    }

    def packageAndPublish(String targetImage, String artifactoryHost) {
        String syntheticDockerfile = """
            FROM ${artifactoryHost}/alpine:latest
            WORKDIR /app
            COPY . . 
            ENTRYPOINT ["./main"]
        """.stripIndent()

        steps.writeFile file: 'Dockerfile.synthetic', text: syntheticDockerfile
        
        steps.sh "docker build -f Dockerfile.synthetic -t ${targetImage} ."
        steps.sh "docker push ${targetImage}"
        steps.sh "docker rmi ${targetImage} || true"
    }
}

