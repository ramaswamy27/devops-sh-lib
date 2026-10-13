package builders

class JavaBuilder implements Serializable {
    def steps
    
    JavaBuilder(steps) {
        this.steps = steps
    }
    
    def build(Map yamlConfig) {
        def nexusUrl = "http://enterprise.internal"
        def version = steps.env.BUILD_NUMBER
        
        // Define a dynamic Kubernetes pod template using standard scripted DSL
        def podXml = """
            apiVersion: v1
            kind: Pod
            spec:
              containers:
              - name: maven-builder
                image: maven:3.9-eclipse-temurin-${yamlConfig.version}
                command: ['cat']
                tty: true
        """
        
        // Execute dynamic execution container matching the requested version
        steps.podTemplate(yaml: podXml) {
            steps.node(steps.POD_LABEL) {
                steps.container('maven-builder') {
                    // Checkout application code inside the builder container context
                    steps.checkout steps.scm 
                    
                    steps.sh "mvn clean package -DskipTests"
                    steps.sh "mvn test"
                    
                    steps.echo "Uploading compiled artifact to Nexus Repository OSS..."
                    steps.withCredentials([steps.usernamePassword(credentialsId: 'nexus-auth-id', passwordVariable: 'NEXUS_PASS', usernameVariable: 'NEXUS_USER')]) {
                        steps.sh """
                            curl -u \$NEXUS_USER:\$NEXUS_PASS --upload-file target/*.jar \
                            ${nexusUrl}/org/enterprise/${yamlConfig.deployment.artifact_name}/${version}/${yamlConfig.deployment.artifact_name}-${version}.jar
                        """
                    }
                }
            }
        }
    }
}
