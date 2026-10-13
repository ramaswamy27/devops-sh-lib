package handlers

class PythonHandler implements Serializable {
    private def steps
    
    PythonHandler(steps) {
        this.steps = steps
    }

    def preBuild(String containerName, def config) {
        steps.sh "docker exec ${containerName} find . -type f -name '*.pyc' -delete || true"
    }

    def build(String containerName, def config) {
        if (!config.buildCmd) { steps.echo "Python stack: No build compilation step required." }
        else { steps.sh "docker exec ${containerName} ${config.buildCmd}" }
    }

    def test(String containerName, def config) {
        if (!config.testCmd) { steps.error "Python Test Command missing." }
        steps.sh "docker exec ${containerName} ${config.testCmd}"
    }

    def packageAndPublish(String targetImage, String artifactoryHost) {
        String syntheticDockerfile = """
            FROM ${artifactoryHost}/python:3.11-slim
            WORKDIR /app
            COPY . .
            ENTRYPOINT ["python", "app.py"]
        """.stripIndent()

        steps.writeFile file: 'Dockerfile.synthetic', text: syntheticDockerfile
        
        steps.sh "docker build -f Dockerfile.synthetic -t ${targetImage} ."
        steps.sh "docker push ${targetImage}"
        steps.sh "docker rmi ${targetImage} || true"
    }
}

