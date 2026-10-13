package handlers

class JavaHandler implements Serializable {
    private def steps
    
    JavaHandler(steps) {
        this.steps = steps
    }

    def preBuild(String containerName, def config) {
        steps.echo "Generating corporate Apache Archiva Maven settings file..."
        
        // Define your exact Archiva administrator authentication password
        String archivaPassword = "Admin@123"
        
        // Your exact XML configuration payload
        String mavenSettings = """<?xml version="1.0" encoding="UTF-8"?>
        <settings xmlns="http://apache.org"
                  xmlns:xsi="http://w3.org"
                  xsi:schemaLocation="http://apache.org https://apache.org">
          
          <mirrors>
            <!-- Redirects all external dependency requests to your Archiva instance -->
            <mirror>
              <id>archiva-mirror</id>
              <name>Local Apache Archiva Mirror</name>
              <url>http://192.168.122.154:8080/repository/internal/</url>
              <mirrorOf>*</mirrorOf>
            </mirror>
          </mirrors>

          <servers>
            <!-- Authentication for downloading from your Archiva repository -->
            <server>
              <id>archiva-mirror</id>
              <username>admin</username>
              <password>${archivaPassword}</password>
            </server>
          </servers>
        </settings>
        """.stripIndent()

        // Write the custom credentials file directly into the isolated workspace path
        steps.writeFile file: 'corporate-maven-settings.xml', text: mavenSettings
    }

    def build(String containerName, def config) {
        if (!config.buildCmd) { steps.error "Java Build Command missing." }
        
        // Execute Maven build explicitly utilizing the generated settings file via -s
        steps.echo "Compiling code against internal Apache Archiva server..."
        steps.sh "docker exec ${containerName} ${config.buildCmd} -s ${steps.env.WORKSPACE}/corporate-maven-settings.xml"
    }

    def test(String containerName, def config) {
        if (!config.testCmd) { steps.error "Java Test Command missing." }
        
        // Enforce the mirror configuration throughout the test phase execution loop
        steps.sh "docker exec ${containerName} ${config.testCmd} -s ${steps.env.WORKSPACE}/corporate-maven-settings.xml"
    }

    def packageAndPublish(String targetImage, String artifactoryHost) {
        // Construct the multi-stage deployment layer binary target wrapper
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

