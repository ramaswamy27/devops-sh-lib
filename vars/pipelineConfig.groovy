#!/usr/bin/groovy

def call(body) {
    // ==========================================
    // 1. ADMIN CENTRAL CONFIGURATIONS & GUARDRAILS
    // ==========================================
    final String ARTIFACTORY_HOST = "192.168.122.154:5000" 
    final String SECURE_SCAN_IMAGE = "aquasec/trivy:latest" 
    final double MAX_CPU = 16.0
    final double MAX_MEMORY_GB = 32.0

    // Target the single massive administrator agent
    node('remote-node-4-ci') {
        currentBuild.result = "SUCCESS"

        stage('Checkout & Validation') {
            checkout scm 
            
            if (!fileExists('pipeline-config.yaml')) {
                error "CRITICAL ERROR: 'pipeline-config.yaml' is missing from the repository root."
            }
            
            def config = readYaml file: 'pipeline-config.yaml'
            
            // Extract and parse resource configurations
            def requestedCpu = (config.resources?.cpu ?: "2").toDouble()
            def requestedMemStr = config.resources?.memory ?: "4Gi"

            double requestedMemGB = 0.0
            String cleanMemStr = requestedMemStr.toUpperCase()
            double numericalValue = cleanMemStr.replaceAll(/[^\d\.]/, '').toDouble()

            if (cleanMemStr.contains("M")) {
                requestedMemGB = numericalValue / 1024.0
            } else if (cleanMemStr.contains("G")) {
                requestedMemGB = numericalValue         
            } else {
                requestedMemGB = numericalValue / (1024.0 * 1024.0 * 1024.0) 
            }

            if (requestedCpu > MAX_CPU || requestedMemGB > MAX_MEMORY_GB) {
                error "SECURITY VIOLATION: Requested resources exceed limits."
            }

            String dockerMemoryFlag = requestedMemStr.toLowerCase().replaceAll(/i/, '')

            // ==========================================
            // FIX #1: MAP DEVELOPER STRINGS TO REAL UPSTREAM DOCKER TAGS
            // ==========================================
            def upstreamImageMap = [
                'java-1.17': 'maven:3.9.6-eclipse-temurin-17',
                'java-1.21': 'maven:3.9.6-eclipse-temurin-21',
                'go-1.21':   'golang:1.21-bookworm',
                'python-3':  'python:3.11-slim',
                'rust-1':    'rust:1.75-slim'
            ]
            
            String stackKey = config.stack?.toLowerCase()
            String versionKey = config.stackVersion?.toLowerCase()
            String lookupString = "${stackKey}-${versionKey}"
            
            // Fallback map if exact match isn't found
            String upstreamTarget = upstreamImageMap[lookupString] ?: upstreamImageMap["${stackKey}-1.21"]
            if (!upstreamTarget) { error "UNSUPPORTED STACK COMBINATION: ${lookupString}" }

            // Dynamic targeted tag matching your corporate infrastructure format
            String buildRuntimeImage = "${ARTIFACTORY_HOST}/${stackKey}:${config.stackVersion}"

            // Check if your registry already contains the image, if not, download and cache it on the fly
            sh """
                docker image inspect ${buildRuntimeImage} >/dev/null 2>&1 || {
                    echo "Base image missing from registry. Pulling ${upstreamTarget} from upstream and caching..."
                    docker pull ${upstreamTarget}
                    docker tag ${upstreamTarget} ${buildRuntimeImage}
                    docker push ${buildRuntimeImage}
                }
            """

            // Runtime reflection class loading for language handlers
            def handler = null
            String className = "handlers.${stackKey.capitalize()}Handler"
            try {
                Class handlerClass = this.class.classLoader.loadClass(className)
                handler = handlerClass.newInstance(this)
            } catch (ClassNotFoundException e) {
                echo "Notice: Optional helper class ${className} not found. Proceeding with standard execution."
            }

            // ==========================================
            // 2. CONTAINERIZED STACK EXECUTION
            // ==========================================
            echo "Executing workloads inside container environment: ${buildRuntimeImage}"
            String buildContainerName = "jenkins-build-${env.JOB_NAME.replaceAll(/[^a-zA-Z0-9]/, '-')}-${env.BUILD_NUMBER}"
            
            try {
                sh "docker run -d --name ${buildContainerName} --net=host --cpus=${requestedCpu} --memory=${dockerMemoryFlag} -v ${env.WORKSPACE}:${env.WORKSPACE} -w ${env.WORKSPACE} ${buildRuntimeImage} tail -f /dev/null"
                
                stage('Pre-Build Steps') {
                    if (handler != null) { handler.preBuild() }
                }
                stage('Build') {
                    sh "docker exec ${buildContainerName} ${config.buildCmd}"
                }
                stage('Test') {
                    sh "docker exec ${buildContainerName} ${config.testCmd}"
                }
            } finally {
                sh "docker rm -f ${buildContainerName} || true"
            }

            // ==========================================
            // 3. MANDATORY ADMIN SECURITY COMPLIANCE
            // ==========================================
            stage('Mandatory Security Scan') {
                String scanContainerName = "jenkins-scan-${env.BUILD_NUMBER}"
                try {
                    sh "docker run --rm --name ${scanContainerName} -v ${env.WORKSPACE}:/apps ${SECURE_SCAN_IMAGE} fs --exit-code 1 --severity HIGH,CRITICAL /apps"
                } catch (Exception e) {
                    error "SECURITY COMPLIANCE FAILURE: Vulnerabilities discovered by admin tool."
                }
            }

            // ==========================================
            // 4. FIX #2: AUTOMATED DOCKERFILE SYNTHESIS
            // ==========================================
            stage('Publish to Artifactory') {
                if (!config.imageName) { error "Configuration Error: 'imageName' is empty." }
                String targetImage = "${ARTIFACTORY_HOST}/${config.imageName}:${env.BRANCH_NAME}-${env.BUILD_NUMBER}"

                // Admin generated enterprise runtime blueprint based on technology type
                String syntheticDockerfile = ""
                switch(stackKey) {
                    case 'java':
                        syntheticDockerfile = """
                            FROM ${ARTIFACTORY_HOST}/eclipse-temurin:21-jre-jammy
                            WORKDIR /app
                            COPY target/*.jar app.jar
                            ENTRYPOINT ["java", "-jar", "app.jar"]
                        """.stripIndent()
                        break
                    case 'go':
                        syntheticDockerfile = """
                            FROM ${ARTIFACTORY_HOST}/alpine:latest
                            WORKDIR /app
                            COPY . . 
                            ENTRYPOINT ["./main"]
                        """.stripIndent()
                        break
                    default:
                        syntheticDockerfile = """
                            FROM ${buildRuntimeImage}
                            WORKDIR /app
                            COPY . .
                        """.stripIndent()
                }

                // Write the synthetic Dockerfile directly into current workspace root folder 
                writeFile file: 'Dockerfile.synthetic', text: syntheticDockerfile
                
                echo "Compiling application runtime image using optimized internal admin template..."
                sh "docker build -f Dockerfile.synthetic -t ${targetImage} ."
                sh "docker push ${targetImage}"
                sh "docker rmi ${targetImage} || true"
                
                echo "Successfully delivered image payload to local Artifactory registry."
            }
        }
    }
}

