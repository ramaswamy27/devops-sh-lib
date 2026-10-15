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

    def packageAndPublish(String targetImage, String artifactoryHost, def config) {
	if (!config.dockerImage || !config.config.imageVer) {
	    steps.error "Pipeline Failure: 'dockerImage' and 'imageVersion' are required in pipeline-config.yaml"
	}
	String baseImage = config.dockerImage
	String baseVer   = config.imageVer
	String baseRuntime = "${ARTIFACTORY_HOST}/${baseImage}:${baseVer}"
        String syntheticDockerfile = """
            FROM ${baseRuntime}
            WORKDIR /app
            COPY . . 
            ENTRYPOINT ["./app-binary"]
        """.stripIndent()

        steps.writeFile file: 'Dockerfile.synthetic', text: syntheticDockerfile
        
        steps.sh "docker build -f Dockerfile.synthetic -t ${targetImage} ."
        steps.sh "docker push ${targetImage}"
        steps.sh "docker rmi ${targetImage} || true"
    }
}

