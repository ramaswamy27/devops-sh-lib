package builders

class JavaBuilder implements Serializable {
    def steps
    
    JavaBuilder(steps) {
        this.steps = steps
    }
    
    def build(Map yamlConfig) {
        // Centralized Nexus definitions
        def nexusUrl = "http://enterprise.internal"
        def version = steps.env.BUILD_NUMBER
        
        // Dynamically spins up the exact Java version using a transient K8s agent
        steps.kubernetes {
            yaml """
            apiVersion: v1
            kind: Pod
            spec:
              containers:
              - name: maven-builder
                image: maven:3.9-eclipse-temurin-${yamlConfig.version}
                command: ['cat']
                tty: true
            """
        }
        
        // Execute steps inside the compiler container context
        steps.container('maven-builder') {
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
