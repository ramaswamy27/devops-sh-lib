package handlers

class JavaHandler implements Serializable {
    private def steps
    
    JavaHandler(steps) {
        this.steps = steps
    }

    def preBuild(String containerName, def config) {
        steps.echo "Initializing clean local cache space on Jenkins Host Agent..."
    }

    def build(String containerName, def config) {
        if (!config.buildCmd) { steps.error "Java Build Command missing." }
        // Clean, direct compilation execution
        steps.sh "docker exec ${containerName} ${config.buildCmd}"
    }

    def test(String containerName, def config) {
        if (!config.testCmd) { steps.error "Java Test Command missing." }
        steps.sh "docker exec ${containerName} ${config.testCmd}"
    }

    def packageAndPublish(String targetImage, String artifactoryHost) {
        // Construct the multi-stage final application runtime image
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

