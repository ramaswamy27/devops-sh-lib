#!/usr/bin/groovy

def call(body) {
    // ==========================================
    // 1. ADMIN CENTRAL CONFIGURATIONS & GUARDRAILS
    // ==========================================
    final String ARTIFACTORY_HOST = "10.0.0.5:5001" 
    final String SECURE_SCAN_IMAGE = "aquasec/trivy:latest" 
    final int MAX_CPU = 16
    final int MAX_MEMORY_GB = 32

    // Target the single massive administrator agent
    node('massive-agent') {
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
            
            // ==========================================
            // FIX: RUNTIME REFLECTION CLASS LOADING
            // ==========================================
            def handler = null
            String className = "handlers.${stackKey.capitalize()}Handler"
            
            try {
                // Dynamically look up the class within the Shared Library classpath at runtime
                Class handlerClass = this.class.classLoader.loadClass(className)
                handler = handlerClass.newInstance(this)
            } catch (ClassNotFoundException e) {
                echo "Notice: Optional helper class ${className} not found. Proceeding with standard execution."
            }

            // ==========================================
            // 2. CONTAINERIZED STACK EXECUTION
            // ==========================================
            echo "Spinning up container runtime [${buildRuntimeImage}] with allocated limits: ${requestedCpu} CPU, ${requestedMemStr} Memory."
            
            docker.image(buildRuntimeImage).inside("--cpus=${requestedCpu} --memory=${requestedMemStr}") {
                
                stage('Pre-Build Steps') {
                    // Only execute if a corresponding Handler class was successfully found
                    if (handler != null) {
                        handler.preBuild() 
                    } else {
                        echo "Skipping specialized pre-build logic."
                    }
                }

                stage('Build') {
                    if (!config.buildCmd) { error "Configuration Error: 'buildCmd' is empty." }
                    sh "${config.buildCmd}"
                }
                
                stage('Test') {
                    if (!config.testCmd) { error "Configuration Error: 'testCmd' is empty." }
                    sh "${config.testCmd}"
                }
            }

            // ==========================================
            // 3. MANDATORY ADMIN SECURITY COMPLIANCE
            // ==========================================
            stage('Mandatory Security Scan') {
                echo "Running required filesystem security auditing via central tooling..."
                docker.image(SECURE_SCAN_IMAGE).inside() {
                    sh "trivy fs --exit-code 1 --severity HIGH,CRITICAL ."
                }
            }

            // ==========================================
            // 4. IMAGE DISTRIBUTION & PUBLISHING
            // ==========================================
            stage('Publish to Artifactory') {
                if (!config.imageName) { error "Configuration Error: 'imageName' must be provided to publish artifacts." }
                
                String targetImage = "${ARTIFACTORY_HOST}/${config.imageName}:${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                echo "Compiling final application image: ${targetImage}"
                
                def appImage = docker.build(targetImage, ".")
                
                docker.withRegistry("http://${ARTIFACTORY_HOST}") {
                    appImage.push()
                }
                echo "Successfully delivered image payload to local Artifactory registry."
            }
        }
    }
}

