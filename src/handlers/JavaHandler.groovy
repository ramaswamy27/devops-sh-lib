package handlers

class JavaHandler implements Serializable {
    private def steps
    
    JavaHandler(steps) {
        this.steps = steps
    }

    def preBuild(String containerName, def config) {
        steps.echo "Initializing custom Java pre-build verifications..."
    }

    def build(String containerName, def config) {
        if (!config.buildCmd) { steps.error "Java Build Command missing." }
        steps.sh "docker exec ${containerName} ${config.buildCmd}"
    }

    def test(String containerName, def config) {
        if (!config.testCmd) { steps.error "Java Test Command missing." }
        steps.sh "docker exec ${containerName} ${config.testCmd}"
    }

    def packageAndPublish(String targetImage, String artifactoryHost) {
        // Auto-generate standard JRE multi-stage assembly file in memory
        String syntheticDockerfile = """
            FROM ${artifactoryHost}/eclipse-temurin:21-jre-jammy
            WORKDIR /app
            COPY target/*.jar app.jar
            ENTRYPOINT ["java", "-jar", "app.jar"]
        """.stripIndent()

        steps.writeFile file: 'Dockerfile.synthetic', text: syntheticDockerfile
        
        steps.sh "docker build -f Dockerfile.synthetic -t ${targetImage} ."
        steps.sh "docker push ${targetImage}"
        steps.sh "docker rmi ${targetImage} || true"
    }
}

