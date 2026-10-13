#!/usr/bin/groovy

def call(body) {
    // ==========================================
    // 1. ADMIN CENTRAL CONFIGURATIONS & GUARDRAILS
    // ==========================================
    final String ARTIFACTORY_HOST = "192.168.122.154:5001" 
    final String SECURE_SCAN_IMAGE = "aquasec/trivy:latest" 
    final int MAX_CPU = 16
    final int MAX_MEMORY_GB = 32

    // Target the single massive administrator agent
    node('remote-node-4-ci') {
        currentBuild.result = "SUCCESS"

        stage('Checkout & Validation') {
            // SCM checkout happens automatically first
            checkout scm 
            
            if (!fileExists('pipeline-config.yaml')) {
                error "CRITICAL ERROR: 'pipeline-config.yaml' is missing from the repository root."
            }
            
            // Native Jenkins plugin step to parse YAML
            def config = readYaml file: 'pipeline-config.yaml'
            
            // Extract and validate container resource requests
            def requestedCpu = (config.resources?.cpu ?: "2").toInteger()
            def requestedMemStr = config.resources?.memory ?: "4Gi"
	    // Convert Kubernetes style 'Gi/Mi' into Docker CLI compatible 'g/m'
	    String dockerMemoryFlag = requestedMemStr.toLowerCase().replaceAll(/i/, '')
            def requestedMem = requestedMemStr.replaceAll(/[^\d]/, '').toInteger()

            if (requestedCpu > MAX_CPU || requestedMem > MAX_MEMORY_GB) {
                error "SECURITY VIOLATION: Requested resources (${requestedCpu} CPU / ${requestedMemStr}) exceed Admin safety limits (Max: ${MAX_CPU} CPU / ${MAX_MEMORY_GB}Gi)."
            }

            // Map stack strings to baseline official Docker images
            def imageMap = [
                'java': 'maven',
                'go': 'golang',
                'python': 'python',
                'rust': 'rust'
            ]
            
            String stackKey = config.stack?.toLowerCase()
            String baseImageName = imageMap[stackKey]
            
            if (!baseImageName) { 
                error "UNSUPPORTED STACK: '${config.stack}' is not registered in the central Admin library. Allowed: java, go, python, rust." 
            }
            
            // Formulate the runtime image using the user-defined version tag
            String buildRuntimeImage = "${baseImageName}:${config.stackVersion}"
            
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
            // 2. CONTAINERIZED STACK EXECUTION (NATIVE)
            // ==========================================
            echo "Spinning up container runtime [${buildRuntimeImage}] with allocated limits: ${requestedCpu} CPU, ${requestedMemStr} Memory."
            
            // Unique container runtime name to prevent overlap collisions
            String buildContainerName = "jenkins-build-${env.JOB_NAME.replaceAll(/[^a-zA-Z0-9]/, '-')}-${env.BUILD_NUMBER}"
            
            try {
                // Spin up application runtime container detached, passing current workspace folder inside
                sh "docker run -d --name ${buildContainerName} --cpus=${requestedCpu} --memory=${dockerMemoryFlag} -v ${env.WORKSPACE}:${env.WORKSPACE} -w ${env.WORKSPACE} ${buildRuntimeImage} tail -f /dev/null"
                
                stage('Pre-Build Steps') {
                    if (handler != null) {
                        handler.preBuild() 
                    } else {
                        echo "Skipping specialized pre-build logic."
                    }
                }

                stage('Build') {
                    if (!config.buildCmd) { error "Configuration Error: 'buildCmd' is empty." }
                    sh "docker exec ${buildContainerName} ${config.buildCmd}"
                }
                
                stage('Test') {
                    if (!config.testCmd) { error "Configuration Error: 'testCmd' is empty." }
                    sh "docker exec ${buildContainerName} ${config.testCmd}"
                }
            } finally {
                // Guaranteed safety cleanup block to ensure agent is never choked by orphaned containers
                sh "docker rm -f ${buildContainerName} || true"
            }

            // ==========================================
            // 3. MANDATORY ADMIN SECURITY COMPLIANCE
            // ==========================================
            stage('Mandatory Security Scan') {
                echo "Running required filesystem security auditing via central tooling..."
                String scanContainerName = "jenkins-scan-${env.BUILD_NUMBER}"
                try {
                    sh "docker run --rm --name ${scanContainerName} -v ${env.WORKSPACE}:/apps ${SECURE_SCAN_IMAGE} fs --exit-code 1 --severity HIGH,CRITICAL /apps"
                } catch (Exception e) {
                    error "SECURITY COMPLIANCE FAILURE: Vulnerabilities discovered by admin tool. Blocking artifact delivery."
                }
            }

            // ==========================================
            // 4. IMAGE DISTRIBUTION & PUBLISHING
            // ==========================================
            stage('Publish to Artifactory') {
                if (!config.imageName) { error "Configuration Error: 'imageName' must be provided to publish artifacts." }
                
                String targetImage = "${ARTIFACTORY_HOST}/${config.imageName}:${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                echo "Compiling final application image: ${targetImage}"
                
                // Native Docker build and push commands directly executed on the massive local agent
                sh "docker build -t ${targetImage} ."
                sh "docker push ${targetImage}"
                sh "docker rmi ${targetImage} || true" // Clean up image cache locally
                
                echo "Successfully delivered image payload to local Artifactory registry."
            }
        }
    }
}

